package com.chencohensolution.transcriptor.speech

import kotlin.math.max
import kotlin.math.sqrt

/** Speech between two pauses: its first sample (16 kHz, from the start of the recording) and its samples. */
class Utterance(val startSample: Long, val samples: FloatArray) {
    val endSample get() = startSample + samples.size
}

/**
 * Where [Utterances] ends a piece: after [pauseMs] of quiet once it holds [minLineMs] of speech, after [longPauseMs]
 * of quiet in any case, past [softMaxMs] at the first [shortPauseMs] of quiet, and at [hardMaxMs] whatever comes.
 */
data class UtteranceLimits(
    val pauseMs: Int,
    val longPauseMs: Int,
    val minLineMs: Int,
    val shortPauseMs: Int,
    val softMaxMs: Int,
    val hardMaxMs: Int,
) {
    companion object {
        /**
         * Lines: a line of at least 3 s ends after 0.6 s of quiet (a shorter one only after 2 s: the model guesses badly
         * from a second of audio), past 15 s at the first short quiet, and never runs past 25 s.
         */
        val lines = UtteranceLimits(
            pauseMs = 600, longPauseMs = 2_000, minLineMs = 3_000, shortPauseMs = 150, softMaxMs = 15_000, hardMaxMs = 25_000,
        )

        /**
         * Windows of about [seconds] for a model too slow for lines but fast enough for whole windows (Whisper's encoder
         * takes 30 s whatever it is given): pauses inside don't end it; it ends at the first short quiet in its last
         * 5 s, at [seconds] whatever comes, or after 5 s of quiet (the speaker stopped: no window of silence).
         */
        fun window(seconds: Int) = UtteranceLimits(
            pauseMs = Int.MAX_VALUE, longPauseMs = 5_000, minLineMs = 0, shortPauseMs = 150,
            softMaxMs = (seconds - 5).coerceAtLeast(1) * 1_000, hardMaxMs = seconds * 1_000,
        )
    }
}

/**
 * Splits 16 kHz audio into utterances at pauses, by loudness against a noise floor that follows the room, ending each
 * where [limits] says (lines by default, or windows). Keeps [PRE_ROLL_MS] before the speech starts so its first word
 * isn't cut. Pieces with less than [MIN_SPEECH_MS] of speech are dropped (a cough, a door).
 */
class Utterances(private val limits: UtteranceLimits = UtteranceLimits.lines) {
    private var position = 0L
    private val frame = FloatArray(FRAME)
    private var inFrame = 0
    private var noise = 0.003f

    private val preRoll = ArrayDeque<FloatArray>()
    private var open: FloatBuilder? = null
    private var openStart = 0L
    private var quietFrames = 0
    private var speechFrames = 0

    /** The utterance being spoken now, if any: its start and length so far. */
    val openStartSample get() = if (open != null) openStart else null
    val openSize get() = open?.size ?: 0

    fun openSnapshot(): Utterance? = open?.let { Utterance(openStart, it.toArray()) }

    /** Feeds samples; returns the utterances they closed. */
    fun feed(samples: FloatArray): List<Utterance> {
        val closed = mutableListOf<Utterance>()
        for (s in samples) {
            frame[inFrame++] = s
            if (inFrame == FRAME) {
                inFrame = 0
                onFrame(frame.copyOf())?.let(closed::add)
            }
        }
        return closed
    }

    /** Ends the open utterance (pause, stop). */
    fun close(): Utterance? {
        val u = open ?: return null
        open = null
        return if (speechFrames * FRAME_MS >= MIN_SPEECH_MS) Utterance(openStart, u.toArray()) else null
    }

    private fun onFrame(f: FloatArray): Utterance? {
        val start = position
        position += FRAME
        var sum = 0.0
        for (x in f) sum += x * x
        val rms = sqrt(sum / FRAME).toFloat()
        val speech = rms > max(noise * 3f, MIN_LEVEL)
        // The noise floor follows quiet frames, and rises only slowly (about a minute) while speech goes on, so a
        // lecturer who never stops doesn't become the floor, and a noisy room still does.
        noise = when {
            rms < noise -> noise * 0.9f + rms * 0.1f
            speech -> noise * 0.9995f + rms * 0.0005f
            else -> noise * 0.95f + rms * 0.05f
        }
        val u = open
        if (u == null) {
            if (speech) {
                val b = FloatBuilder()
                openStart = start - preRoll.sumOf { it.size }
                preRoll.forEach(b::add)
                preRoll.clear()
                b.add(f)
                open = b
                quietFrames = 0
                speechFrames = 1
            } else {
                preRoll.addLast(f)
                if (preRoll.size * FRAME_MS > PRE_ROLL_MS) preRoll.removeFirst()
            }
            return null
        }
        u.add(f)
        if (speech) { speechFrames++; quietFrames = 0 } else quietFrames++
        val lengthMs = u.size / 16
        val quietMs = quietFrames * FRAME_MS
        val l = limits
        val end = (quietMs >= l.pauseMs && lengthMs - quietMs >= l.minLineMs) || quietMs >= l.longPauseMs ||
            (lengthMs >= l.softMaxMs && quietMs >= l.shortPauseMs) ||
            lengthMs >= l.hardMaxMs
        return if (end) close() else null
    }

    companion object {
        const val FRAME = 480 // 30 ms
        const val FRAME_MS = 30
        const val PRE_ROLL_MS = 240
        const val MIN_SPEECH_MS = 300
        const val MIN_LEVEL = 0.004f
    }
}

/** A growing FloatArray. */
class FloatBuilder {
    private var data = FloatArray(16_000)
    var size = 0
        private set

    fun add(values: FloatArray) {
        if (size + values.size > data.size) data = data.copyOf(max(data.size * 2, size + values.size))
        values.copyInto(data, size)
        size += values.size
    }

    fun toArray() = data.copyOf(size)

    /** The values so far, and empties the builder. */
    fun drain(): FloatArray = toArray().also { size = 0 }

    /** Values [from] until [to]. */
    fun slice(from: Int, to: Int): FloatArray = data.copyOfRange(from, to)

    /** Copies values [from] until [to] into [destination] at [at]. */
    fun copyInto(destination: FloatArray, at: Int, from: Int, to: Int) {
        data.copyInto(destination, at, from, to)
    }

    /** Drops the first [n] values. */
    fun dropFirst(n: Int) {
        data.copyInto(data, 0, n, size)
        size -= n
    }
}
