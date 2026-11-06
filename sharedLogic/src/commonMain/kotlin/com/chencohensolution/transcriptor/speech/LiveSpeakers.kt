package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Segment
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Who speaks in one window of audio, frame by frame, as a segmentation model (pyannote segmentation 3.0) tells it:
 * [active] has a row per frame, a flag per local speaker (the window's own numbering, not stable between windows).
 * Frame i covers samples i * [frameShift] to i * [frameShift] + [frameSize] of the window.
 */
class Segmentation(val frameShift: Int, val frameSize: Int, val active: List<BooleanArray>) {
    val speakers get() = active.firstOrNull()?.size ?: 0

    /** Frame i's middle, in samples from the window's start. */
    fun center(i: Int) = i * frameShift + frameSize / 2
}

/** A segmentation model on one window of [windowSize] samples at 16 kHz (pyannote: 10 s); a fake in tests. */
interface Segmenter : AutoCloseable {
    val windowSize: Int
    fun segment(window: FloatArray): Segmentation
}

/**
 * pyannote's powerset output to who speaks: each frame's most likely class ([numClasses] log-probabilities per
 * frame), each class a set of up to [maxPerFrame] of [speakers] local speakers, in pyannote's order: none, each
 * speaker alone, then each pair (0+1, 0+2, 1+2), and so on.
 */
fun powersetToActive(logProbs: FloatArray, numClasses: Int, speakers: Int = 3, maxPerFrame: Int = 2): List<BooleanArray> {
    val sets = mutableListOf<List<Int>>(emptyList())
    fun combos(k: Int, from: Int, acc: List<Int>) {
        if (acc.size == k) { sets += acc; return }
        for (s in from until speakers) combos(k, s + 1, acc + s)
    }
    for (k in 1..maxPerFrame) combos(k, 0, emptyList())
    require(sets.size == numClasses) { "powerset of $speakers speakers, $maxPerFrame at once, isn't $numClasses classes" }
    val frames = logProbs.size / numClasses
    return List(frames) { f ->
        var best = 0
        for (c in 1 until numClasses) if (logProbs[f * numClasses + c] > logProbs[f * numClasses + best]) best = c
        BooleanArray(speakers) { it in sets[best] }
    }
}

/** A stretch of the recording ([startMillis] to [endMillis]) said by [speaker]. */
data class LiveTurn(val startMillis: Long, val endMillis: Long, val speaker: String)

/**
 * One voice in one settled part, as [LiveSpeakers] decided it: from [startMillis] to [endMillis] (its turns there),
 * the lecture's [speaker], and either its [print] or, without enough audio for one, the earlier stretch it continues
 * ([follows], an index). Kept with the lecture, so its speakers can be regrouped at other thresholds without the audio
 * ([replayStretches]).
 */
data class LiveStretch(
    val startMillis: Long,
    val endMillis: Long,
    val speaker: String,
    val print: List<Float>? = null,
    val follows: Int? = null,
)

/**
 * Speaker labels while recording, diart's way: every [STEP] of audio the segmentation model reads the last [Segmenter.windowSize]
 * (10 s), and the part of it [LOOKAHEAD] old or older, not settled before, is settled: who speaks there, overlaps
 * included. Each of the window's voices there gets one voice print of its own, single-voice audio in that part (when
 * it has [MIN_PRINT] of it) and joins the closest of the lecture's speakers ([SpeakerTracker]: running averages,
 * known voices first), or starts a new one. A voice with too little audio for a print keeps the speaker it had in the
 * previous window, where the two windows overlap. So each moment is decided once, with [LOOKAHEAD] of context after
 * it; labels follow the speech by [LOOKAHEAD] to [LOOKAHEAD] + [STEP].
 *
 * Audio comes from the recorder's thread ([feed], or [feed16k]); the models run on [worker]. [turns] grows as stretches
 * settle; [settledMillis] is how far.
 */
class LiveSpeakers(
    private val tracker: SpeakerTracker,
    scope: CoroutineScope,
    worker: CoroutineDispatcher,
    private val loadSegmenter: () -> Segmenter,
    private val loadEmbedder: () -> VoiceEmbedder,
) {
    class State(
        val turns: List<LiveTurn> = emptyList(),
        val settledMillis: Long = 0,
        val error: String? = null,
        val stretches: List<LiveStretch> = emptyList(),
    )

    private val downsampler = Downsampler()
    private val lock = SynchronizedObject()
    private val pending = FloatBuilder()
    private var finishing = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // The worker's own.
    private val audio = FloatBuilder() // from sample [audioStart] of the recording
    private var audioStart = 0L
    private var total = 0L
    private var settled = 0L
    private var nextRun = 0L
    /** The previous window's voices: local speaker to its frames (recording samples), speaker and stretch index. */
    private var previous: Map<Int, Triple<List<Long>, String, Int>> = emptyMap()
    private val stretches = mutableListOf<LiveStretch>()
    private var segmenter: Segmenter? = null
    private var embedder: VoiceEmbedder? = null

    /** Prints made, and the time they took, for the A/B. */
    var prints = 0
        private set
    var windows = 0
        private set

    private val job: Job = scope.launch(worker) { run() }

    fun feed(pcm: ByteArray, size: Int) = feed16k(downsampler.process(pcm, size))

    fun feed16k(samples: FloatArray) {
        synchronized(lock) { pending.add(samples) }
        wake.trySend(Unit)
    }

    /** Settles everything fed, the end without [LOOKAHEAD], and stops. */
    suspend fun finish() {
        synchronized(lock) { finishing = true }
        wake.trySend(Unit)
        job.join()
    }

    private suspend fun run() {
        try {
            segmenter = loadSegmenter()
            embedder = loadEmbedder()
        } catch (e: Throwable) {
            _state.update { State(it.turns, it.settledMillis, e.message ?: e.toString(), it.stretches) }
            close()
            return
        }
        try {
            while (true) {
                val (samples, finish) = synchronized(lock) { pending.drain() to finishing }
                if (samples.isNotEmpty()) {
                    audio.add(samples)
                    total += samples.size
                }
                val window = segmenter!!.windowSize
                if (nextRun == 0L) nextRun = minOf(window.toLong(), STEP.toLong() + LOOKAHEAD)
                while (total >= nextRun) {
                    step(nextRun, nextRun - LOOKAHEAD)
                    nextRun += STEP
                }
                if (finish) {
                    if (total > settled) step(total, total)
                    break
                }
                wake.receive()
            }
        } catch (e: Throwable) {
            _state.update { State(it.turns, it.settledMillis, e.message ?: e.toString(), it.stretches) }
        } finally {
            close()
        }
    }

    private fun close() {
        segmenter?.close()
        embedder?.close()
        segmenter = null
        embedder = null
    }

    /** Segments the window ending at sample [end] and settles it up to [until]. */
    private fun step(end: Long, until: Long) {
        val seg = segmenter!!
        val size = seg.windowSize
        val start = end - size
        // The window, zeros before the recording's start.
        val window = FloatArray(size)
        val from = maxOf(start, audioStart)
        audio.copyInto(window, (from - start).toInt(), (from - audioStart).toInt(), (end - audioStart).toInt())
        val s = seg.segment(window)
        windows++
        val turns = settle(s, start, settled, until)
        settled = maxOf(settled, until)
        // Keep a window of audio before what is still to come.
        val keep = settled - size
        if (keep > audioStart) {
            audio.dropFirst((keep - audioStart).toInt())
            audioStart = keep
        }
        _state.update { State(it.turns + turns, settled / 16, it.error, stretches.toList()) }
    }

    /** The window's voices in [from, until) (recording samples), as the lecture's speakers. */
    private fun settle(s: Segmentation, start: Long, from: Long, until: Long): List<LiveTurn> {
        if (until <= from) return emptyList()
        val frames = s.active.indices.filter { start + s.center(it) in from until until }
        val ids = mutableMapOf<Int, String>()
        val now = mutableMapOf<Int, Triple<List<Long>, String, Int>>()
        for (k in 0 until s.speakers) {
            val own = s.active.indices.filter { s.active[it][k] }.map { start + s.center(it) }
            val inPart = frames.filter { s.active[it][k] }
            if (inPart.isEmpty()) continue
            // Its single-voice audio in the part, for its print.
            val clean = FloatBuilder()
            for (f in inPart) {
                if (s.active[f].count { it } != 1) continue
                val a = start + f * s.frameShift
                val b = minOf(a + s.frameShift, until)
                if (a < audioStart || b > audioStart + audio.size) continue
                clean.add(audio.slice((a - audioStart).toInt(), (b - audioStart).toInt()))
            }
            val partStart = (inPart.minOf { start + s.center(it) } - s.frameShift / 2).coerceAtLeast(from) / 16
            val partEnd = (inPart.maxOf { start + s.center(it) } + s.frameShift / 2).coerceAtMost(until) / 16
            val stretch = if (clean.size >= MIN_PRINT) {
                prints++
                val print = embedder!!.embed(clean.toArray())
                LiveStretch(partStart, partEnd, tracker.assign(print), print = print.toList())
            } else {
                // The previous window's voice this one overlaps most.
                val before = previous.values.maxByOrNull { (times, _, _) -> times.count { it in own } }
                    ?.takeIf { (times, _, _) -> times.any { it in own } } ?: continue
                LiveStretch(partStart, partEnd, before.second, follows = before.third)
            }
            stretches += stretch
            ids[k] = stretch.speaker
            now[k] = Triple(own, stretch.speaker, stretches.lastIndex)
        }
        previous = now
        // Turns: runs of frames with the same speaker, per speaker.
        val out = mutableListOf<LiveTurn>()
        for ((k, id) in ids) {
            var runStart = -1L
            var runEnd = -1L
            for (f in frames) {
                val t = start + s.center(f)
                if (s.active[f][k]) {
                    if (runStart < 0) runStart = maxOf(from, t - s.frameShift / 2)
                    runEnd = minOf(until, t + s.frameShift / 2)
                } else if (runStart >= 0) {
                    out += LiveTurn(runStart / 16, runEnd / 16, id)
                    runStart = -1
                }
            }
            if (runStart >= 0) out += LiveTurn(runStart / 16, runEnd / 16, id)
        }
        return out.sortedBy { it.startMillis }
    }

    companion object {
        /** How often the window moves: 2.5 s. */
        const val STEP = 40_000

        /** How much audio after a moment is heard before it is settled: 2.5 s. */
        const val LOOKAHEAD = 40_000

        /** Single-voice audio needed for a print: 1 s. */
        const val MIN_PRINT = 16_000
    }
}

/**
 * The live lines with the speakers of the settled [turns] (up to [settledMillis]): a line with word times is split
 * where the speaker changes, a line without them gets the speaker who spoke most of it ([followTurns]). Lines not yet
 * settled keep the speaker they have (none). A split line's parts are `<id>`, `<id>.2`, `<id>.3`…
 */
fun liveLines(lines: List<Segment>, turns: List<LiveTurn>, settledMillis: Long): List<Segment> {
    val asTurns = turns.map { Turn(it.startMillis, it.endMillis, it.speaker) }
    return lines.flatMap { line ->
        if (line.endMillis > settledMillis) return@flatMap listOf(line)
        val near = asTurns.filter { it.endMillis >= line.startMillis && it.startMillis <= line.endMillis }
        listOf(line).followTurns(near).mapIndexed { i, s -> s.copy(id = if (i == 0) line.id else "${line.id}.${i + 1}") }
    }
}

/**
 * [stretches]' speakers again, as a [SpeakerTracker] at [threshold] would have made them live: the printed stretches
 * in order, each following one taking its earlier stretch's speaker. Speaker ids are the tracker's ("speaker-N").
 */
fun replayStretches(stretches: List<LiveStretch>, threshold: Float): List<String?> {
    val tracker = SpeakerTracker(threshold = threshold)
    val out = arrayOfNulls<String>(stretches.size)
    stretches.forEachIndexed { i, s ->
        out[i] = s.print?.let { tracker.assign(it.toFloatArray()) } ?: s.follows?.let { f -> out.getOrNull(f) }
    }
    return out.toList()
}
