package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Word
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A loaded streaming model (sherpa-onnx's online recognizer on the phone; a fake in tests): it takes audio as it
 * comes and keeps the text of the line so far. Not thread-safe: one thread.
 */
interface StreamingEngine : AutoCloseable {
    /** 16 kHz mono, -1..1; decodes what it can. */
    fun accept(samples: FloatArray)

    /** The line so far. */
    val text: String

    /** The line's tokens so far, each with when it was said, in seconds from the line's start (the last [reset]). */
    val tokens: List<TimedToken>

    /** The model's own end of a line: a pause after speech, or a line too long. */
    fun isEndpoint(): Boolean

    /** Starts a new line. */
    fun reset()

    /** No more audio for this line: decodes what is left. */
    fun flush()
}

/** A piece of a word (a word starts with a space) and when it was said. */
data class TimedToken(val text: String, val seconds: Float)

/**
 * Live transcription with a streaming model (Zipformer): every buffer goes to the model as it arrives, so the
 * words of the line being spoken appear within about a second, and the model's endpoint (a pause) ends the line.
 * A line runs from its first word to its last, by the model's word times (without them, from when its first words
 * appeared, less the model's chunk, to when its last did). With speaker labels, a line where the speaker changes is
 * split there, at a word ([SpeakerLabeler.split]). Pause and Stop end the line too.
 */
class StreamingTranscriber(
    private val modelId: String,
    scope: CoroutineScope,
    worker: CoroutineDispatcher,
    private val speakers: SpeakerLabeler? = null,
    private val loadEngine: () -> StreamingEngine,
) : LiveTranscriber {
    private val downsampler = Downsampler()
    private val lock = SynchronizedObject()
    private val pending = FloatBuilder()
    private var fed = 0L
    private var lineBreak = false
    private var finishing = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _output = MutableStateFlow(LiveOutput())
    override val output: StateFlow<LiveOutput> = _output

    // The worker's own: samples given to the model, and where the line being spoken began and last changed.
    private var consumed = 0L
    private var lineStart = 0L
    private var firstWordsAt: Long? = null
    private var lastChangeAt = 0L
    private var lastText = ""
    /** The line's audio, from [lineStart], for its voice print. */
    private val lineAudio = FloatBuilder()

    private val job: Job = scope.launch(worker) { run() }

    override fun feed(pcm: ByteArray, size: Int) {
        val samples = downsampler.process(pcm, size)
        synchronized(lock) {
            pending.add(samples)
            fed += samples.size
        }
        wake.trySend(Unit)
    }

    override fun pause() {
        synchronized(lock) { lineBreak = true }
        wake.trySend(Unit)
    }

    override suspend fun finish() {
        synchronized(lock) { finishing = true }
        wake.trySend(Unit)
        job.join()
    }

    private suspend fun run() {
        val engine = try {
            loadEngine()
        } catch (e: Throwable) {
            _output.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            return
        }
        _output.update { it.copy(loading = false) }
        try {
            while (true) {
                val (samples, breakLine, finish) = synchronized(lock) {
                    val taken = pending.drain()
                    val b = lineBreak
                    lineBreak = false
                    Triple(taken, b, finishing)
                }
                if (samples.isNotEmpty()) {
                    engine.accept(samples)
                    consumed += samples.size
                    lineAudio.add(samples)
                    val text = engine.text.trim()
                    if (text != lastText) {
                        if (firstWordsAt == null && text.isNotEmpty()) firstWordsAt = consumed
                        lastChangeAt = consumed
                        lastText = text
                    }
                    if (engine.isEndpoint()) endLine(engine)
                    _output.update { it.copy(partial = lastText.ifEmpty { null }, lagMillis = lag()) }
                }
                if (finish) {
                    engine.flush()
                    lastText = engine.text.trim()
                    if (lastText.isNotEmpty() && firstWordsAt == null) firstWordsAt = consumed
                    lastChangeAt = consumed
                    endLine(engine)
                    _output.update { it.copy(partial = null, lagMillis = 0) }
                    break
                }
                if (breakLine) {
                    endLine(engine)
                    _output.update { it.copy(partial = null) }
                }
                if (samples.isEmpty()) wake.receive()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _output.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            engine.close()
            speakers?.close()
        }
    }

    private fun endLine(engine: StreamingEngine) {
        val text = cleanLine(lastText)
        val first = firstWordsAt
        // Every token goes into the text (punctuation, a lone space); words alone set the times.
        val allTokens = engine.tokens
        val tokens = allTokens.filter { it.text.any(Char::isLetterOrDigit) }
        val audio = lineAudio.drain()
        if (text.isNotEmpty() && first != null) {
            val lines = if (tokens.isNotEmpty()) {
                // Word times are from the line's start: a word, at its sample in the line's audio.
                fun at(t: TimedToken) = (t.seconds * 16_000).toLong()
                val start = (at(tokens.first()) - LEAD_SAMPLES).coerceAtLeast(0)
                val end = minOf(consumed - lineStart, at(tokens.last()) + TAIL_SAMPLES)
                val wordStarts = tokens.filter { it.text.startsWith(" ") || it.text.startsWith("▁") }.map { (at(it) - start).toInt() }
                val parts = speakers?.split(audio.copyOfRange(start.toInt().coerceIn(0, audio.size), end.toInt().coerceIn(0, audio.size)), wordStarts.filter { it > 0 })
                    ?: listOf(SpeakerPart(0, (end - start).toInt(), null))
                parts.mapNotNull { part ->
                    val words = allTokens.filter { at(it) - start in part.start until part.end || (part.end == parts.last().end && at(it) - start >= part.end) }
                    val t = cleanLine(words.joinToString("") { it.text.replace('▁', ' ') })
                    if (t.isEmpty()) null else Triple(lineStart + start + part.start, lineStart + start + part.end, Triple(t, part.speaker, wordsOf(words)))
                }
            } else {
                val start = maxOf(lineStart, first - CHUNK_SAMPLES)
                val end = maxOf(start, minOf(consumed, lastChangeAt))
                val speaker = speakers?.label(audio.copyOfRange((start - lineStart).toInt().coerceIn(0, audio.size), (end - lineStart).toInt().coerceIn(0, audio.size)))
                listOf(Triple(start, end, Triple(text, speaker, emptyList<Word>())))
            }
            _output.update { out ->
                out.copy(
                    segments = out.segments + lines.mapIndexed { i, (start, end, line) ->
                        Segment(
                            id = "s${out.segments.size + i + 1}", startMillis = start / 16, endMillis = end / 16,
                            text = line.first, modelId = modelId, speakerId = line.second, words = line.third,
                        )
                    },
                    partial = null,
                    speakers = speakers?.tracker?.speakers ?: out.speakers,
                )
            }
        }
        engine.reset()
        lineStart = consumed
        firstWordsAt = null
        lastText = ""
    }

    /** Tokens to words (a token starting with a space starts one), each at its first token's time, in the recording. */
    private fun wordsOf(tokens: List<TimedToken>): List<Word> {
        val words = mutableListOf<Word>()
        var space = true
        for (t in tokens) {
            val piece = t.text.replace('▁', ' ')
            if (piece.isBlank()) { space = true; continue }
            val at = lineStart / 16 + (t.seconds * 1000).toLong()
            if (space || piece.startsWith(" ")) words += Word(at, piece.trim())
            else words[words.lastIndex] = words.last().copy(text = words.last().text + piece)
            space = piece.endsWith(" ")
        }
        return words.filter { w -> w.text.any(Char::isLetterOrDigit) }
    }

    private fun lag() = ((synchronized(lock) { fed } - consumed) / 16).coerceAtLeast(0)

    companion object {
        /** The model decodes 1.28 s at a time: words appear up to that long after they were said. */
        const val CHUNK_SAMPLES = 20_480L

        /** A word's time is about when it starts: a line starts a little before its first, ends a little after its last. */
        const val LEAD_SAMPLES = 3_200L
        const val TAIL_SAMPLES = 8_000L
    }
}
