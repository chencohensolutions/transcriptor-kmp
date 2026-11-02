package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Segment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** What live transcription has so far. */
data class LiveOutput(
    val segments: List<Segment> = emptyList(),
    /** The line being spoken now, transcribed so far; null between lines. */
    val partial: String? = null,
    /** How far the text is behind the audio; 0 when it has caught up. */
    val lagMillis: Long = 0,
    /** The model is still loading. */
    val loading: Boolean = true,
    /** The model failed to load or run. */
    val error: String? = null,
)

/**
 * Live transcription of a lecture: the recording's PCM ([feed], from the recorder's thread) is downsampled, split
 * into utterances at pauses, and each closed utterance becomes a [Segment] (one line, with its start and end in the
 * recording). While nothing closed is waiting, the utterance being spoken is transcribed again every
 * [PARTIAL_EVERY_MS] of new audio, for the grey line, until it is [MAX_PARTIAL_MS] long: each partial costs as much
 * as the line so far, so a long line waits for its end. The engine runs on [worker], one call at a time; closed
 * utterances go first. Pauses don't reach [feed], so sample counts are the position in the audio.
 */
class LiveTranscriber(
    private val language: String,
    private val modelId: String,
    scope: CoroutineScope,
    worker: CoroutineDispatcher,
    private val loadEngine: () -> SpeechEngine,
) {
    private val downsampler = Downsampler()
    private val utterances = Utterances()
    private val queue = ArrayDeque<Utterance>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var fed = 0L
    private var lastPartialSize = 0
    private var finishing = false
    private val _output = MutableStateFlow(LiveOutput())
    val output: StateFlow<LiveOutput> = _output

    private val job: Job = scope.launch(worker) { run() }

    /** From the recorder's thread. */
    fun feed(pcm: ByteArray, size: Int) {
        val samples = downsampler.process(pcm, size)
        synchronizedQueue {
            fed += samples.size
            queue.addAll(utterances.feed(samples))
        }
        wake.trySend(Unit)
    }

    /** Ends the line being spoken (Pause). */
    fun pause() {
        synchronizedQueue { utterances.close()?.let(queue::addLast) }
        wake.trySend(Unit)
    }

    /** Ends the line being spoken, transcribes everything still waiting, and stops. */
    suspend fun finish() {
        pause()
        synchronizedQueue { finishing = true }
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
                val next = synchronizedQueue { queue.removeFirstOrNull() }
                if (next != null) {
                    val text = transcribe(engine, next)
                    _output.update { out ->
                        val segments = if (text.isEmpty()) out.segments else out.segments + Segment(
                            id = "s${out.segments.size + 1}",
                            startMillis = next.startSample / 16, endMillis = next.endSample / 16,
                            text = text, modelId = modelId,
                        )
                        out.copy(segments = segments, partial = null, lagMillis = lag(next.endSample))
                    }
                    lastPartialSize = 0
                    continue
                }
                if (synchronizedQueue { finishing }) break
                val open = synchronizedQueue { utterances.openSnapshot() }
                if (open != null && open.samples.size >= MIN_PARTIAL_SAMPLES &&
                    open.samples.size <= MAX_PARTIAL_MS * 16 &&
                    open.samples.size - lastPartialSize >= PARTIAL_EVERY_MS * 16
                ) {
                    lastPartialSize = open.samples.size
                    val text = transcribe(engine, open)
                    // The line may have closed meanwhile; its final text replaces this.
                    if (synchronizedQueue { utterances.openStartSample } == open.startSample) {
                        _output.update { it.copy(partial = text.ifEmpty { null }, lagMillis = lag(open.endSample)) }
                    }
                    continue
                }
                _output.update { it.copy(lagMillis = if (open == null) 0 else lag(open.startSample)) }
                wake.receive()
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _output.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            engine.close()
        }
    }

    // No prompt from the lines before: with one, the model continues mid-sentence (lines starting with ", ") and
    // carries a repetition from line to line.
    private fun transcribe(engine: SpeechEngine, u: Utterance): String {
        val raw = engine.transcribe(u.samples, language, null).filter { it.noSpeech < NO_SPEECH }.joinToString(" ") { it.text.trim() }
        // More words than anyone can say in the time is the model inventing (often a phrase over and over), counted
        // before the tidying shortens a loop.
        val words = raw.split(' ').count { it.any(Char::isLetterOrDigit) }
        return if (words > 2 + u.samples.size / 16_000.0 * MAX_WORDS_PER_SECOND) "" else cleanLine(raw)
    }

    private fun lag(doneUpToSample: Long) = ((synchronizedQueue { fed } - doneUpToSample) / 16).coerceAtLeast(0)

    /** Guards the queue, the utterances and the counters, shared by the recorder's thread and the worker. */
    private val queueLock = SynchronizedObject()
    private inline fun <T> synchronizedQueue(block: () -> T): T = synchronized(queueLock) { block() }

    companion object {
        /** Pieces the model rates above this as probably not speech are dropped ("תודה רבה." out of noise). */
        const val NO_SPEECH = 0.6f
        const val MAX_WORDS_PER_SECOND = 4.5
        const val PARTIAL_EVERY_MS = 2_000
        const val MAX_PARTIAL_MS = 10_000
        const val MIN_PARTIAL_SAMPLES = 16_000
    }
}

/**
 * A line as the model wrote it, tidied: no punctuation or spaces before the first word, and a word or phrase (up to
 * eight words) said three or more times in a row (a model stuck in a loop: "אתם, אתם, אתם") kept once.
 */
fun cleanLine(text: String): String {
    var t = text.trim().trimStart { !it.isLetterOrDigit() }.replace(Regex("""\s+"""), " ")
    t = Regex("""((?<!\S)[^\s,.]+(?:\s+[^\s,.]+){0,7}[,.]?)(?:\s+\1){2,}""").replace(t) { it.groupValues[1] }
    return t.trim()
}
