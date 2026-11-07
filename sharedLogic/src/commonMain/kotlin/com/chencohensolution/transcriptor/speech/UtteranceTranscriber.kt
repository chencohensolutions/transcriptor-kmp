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

/**
 * Live transcription with a model that takes a line at a time (Whisper): the recording's PCM ([feed], from the
 * recorder's thread) is downsampled, split into utterances at pauses, and each closed utterance becomes a [Segment]
 * (one line, with its start and end in the recording). While nothing closed is waiting, the utterance being spoken is transcribed again every
 * [PARTIAL_EVERY_MS] of new audio, for the grey line, until it is [MAX_PARTIAL_MS] long: each partial costs as much
 * as the line so far, so a long line waits for its end. The engine runs on [worker], one call at a time; closed
 * utterances go first. Pauses don't reach [feed], so sample counts are the position in the audio.
 *
 * With [UtteranceLimits.window] in [limits] (a model too slow for lines,
 * [com.chencohensolution.transcriptor.models.SpeechModel.liveWindowSeconds]) the pieces
 * are windows of about 30 s, each one line, and there is no grey line: a partial would cost a whole window. Windows the
 * engine hasn't reached wait in the queue (none is dropped; the recording goes on), and each line shows when it's done.
 */
class UtteranceTranscriber(
    private val language: String,
    private val modelId: String,
    scope: CoroutineScope,
    worker: CoroutineDispatcher,
    private val speakers: SpeakerLabeler? = null,
    private val limits: UtteranceLimits = UtteranceLimits.lines,
    private val loadEngine: () -> SpeechEngine,
) : LiveTranscriber {
    private val downsampler = Downsampler()
    private val utterances = Utterances(limits)
    private val partials = limits == UtteranceLimits.lines
    private val queue = ArrayDeque<Utterance>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var fed = 0L
    private var lastPartialSize = 0
    private var finishing = false
    private val _output = MutableStateFlow(LiveOutput())
    override val output: StateFlow<LiveOutput> = _output

    private val job: Job = scope.launch(worker) { run() }

    override fun feed(pcm: ByteArray, size: Int) {
        val samples = downsampler.process(pcm, size)
        synchronizedQueue {
            fed += samples.size
            queue.addAll(utterances.feed(samples))
        }
        wake.trySend(Unit)
    }

    override fun pause() {
        synchronizedQueue { utterances.close()?.let(queue::addLast) }
        wake.trySend(Unit)
    }

    override suspend fun finish() {
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
                    val speaker = if (text.isEmpty()) null else speakers?.label(next.samples)
                    _output.update { out ->
                        val segments = if (text.isEmpty()) out.segments else out.segments + Segment(
                            id = "s${out.segments.size + 1}",
                            startMillis = next.startSample / 16, endMillis = next.endSample / 16,
                            text = text, modelId = modelId, speakerId = speaker,
                        )
                        out.copy(
                            segments = segments, partial = null, lagMillis = lag(next.endSample),
                            speakers = speakers?.tracker?.speakers ?: out.speakers,
                        )
                    }
                    lastPartialSize = 0
                    continue
                }
                if (synchronizedQueue { finishing }) break
                val open = synchronizedQueue { utterances.openSnapshot() }
                if (partials && open != null && open.samples.size >= MIN_PARTIAL_SAMPLES &&
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
            speakers?.close()
        }
    }

    // No prompt from the lines before: with one, the model continues mid-sentence (lines starting with ", ") and
    // carries a repetition from line to line.
    private fun transcribe(engine: SpeechEngine, u: Utterance): String = transcribeLine(engine, u.samples, language)

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
 * One line's text from a Whisper model, without what it invents: pieces it rates as probably not speech are dropped,
 * and a line with more words than anyone can say in its time (often a phrase over and over, counted before the tidying
 * shortens a loop) gives nothing. Live transcription and the refine pass both use it.
 */
fun transcribeLine(engine: SpeechEngine, samples: FloatArray, language: String): String {
    val raw = engine.transcribe(samples, language, null).filter { it.noSpeech < UtteranceTranscriber.NO_SPEECH }.joinToString(" ") { it.text.trim() }
    val words = raw.split(' ').count { it.any(Char::isLetterOrDigit) }
    return if (words > 2 + samples.size / 16_000.0 * UtteranceTranscriber.MAX_WORDS_PER_SECOND) "" else cleanLine(raw)
}
