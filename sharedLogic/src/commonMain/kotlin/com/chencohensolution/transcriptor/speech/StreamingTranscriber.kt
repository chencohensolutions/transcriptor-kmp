package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Segment
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

    /** The model's own end of a line: a pause after speech, or a line too long. */
    fun isEndpoint(): Boolean

    /** Starts a new line. */
    fun reset()

    /** No more audio for this line: decodes what is left. */
    fun flush()
}

/**
 * Live transcription with a streaming model (Zipformer): every buffer goes to the model as it arrives, so the
 * words of the line being spoken appear within about a second, and the model's endpoint (a pause) ends the line.
 * A line's start is when its first words appeared, less the model's chunk; its end, when its last words did.
 * Pause and Stop end the line too.
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
                    if (speakers != null) lineAudio.add(samples)
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
        if (text.isNotEmpty() && first != null) {
            val start = maxOf(lineStart, first - CHUNK_SAMPLES)
            val end = maxOf(start, minOf(consumed, lastChangeAt))
            val speaker = speakers?.let { labeler ->
                val audio = lineAudio.drain()
                labeler.label(audio.copyOfRange((start - lineStart).toInt().coerceIn(0, audio.size), (end - lineStart).toInt().coerceIn(0, audio.size)))
            }
            _output.update { out ->
                out.copy(
                    segments = out.segments + Segment(
                        id = "s${out.segments.size + 1}", startMillis = start / 16, endMillis = end / 16,
                        text = text, modelId = modelId, speakerId = speaker,
                    ),
                    partial = null,
                    speakers = speakers?.tracker?.speakers ?: out.speakers,
                )
            }
        }
        lineAudio.drain()
        engine.reset()
        lineStart = consumed
        firstWordsAt = null
        lastText = ""
    }

    private fun lag() = ((synchronized(lock) { fed } - consumed) / 16).coerceAtLeast(0)

    companion object {
        /** The model decodes 1.28 s at a time: words appear up to that long after they were said. */
        const val CHUNK_SAMPLES = 20_480L
    }
}
