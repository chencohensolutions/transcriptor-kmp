package com.chencohensolution.transcriptor.speech

import kotlin.math.max
import kotlin.math.sqrt

/** Speech between two pauses: its first sample (16 kHz, from the start of the recording) and its samples. */
class Utterance(val startSample: Long, val samples: FloatArray) {
    val endSample get() = startSample + samples.size
}

/**
 * Splits 16 kHz audio into utterances at pauses, by loudness against a noise floor that follows the room: a line
 * of at least [MIN_LINE_MS] ends after [PAUSE_MS] of quiet (a shorter one only after [LONG_PAUSE_MS]: the model
 * guesses badly from a second of audio), or, past [SOFT_MAX_MS], at the first short quiet, and never runs past
 * [HARD_MAX_MS]. Keeps [PRE_ROLL_MS] before the speech starts so its first word isn't cut. Pieces with less than
 * [MIN_SPEECH_MS] of speech are dropped (a cough, a door).
 */
class Utterances {
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
        val end = (quietMs >= PAUSE_MS && lengthMs - quietMs >= MIN_LINE_MS) || quietMs >= LONG_PAUSE_MS ||
            (lengthMs >= SOFT_MAX_MS && quietMs >= SHORT_PAUSE_MS) ||
            lengthMs >= HARD_MAX_MS
        return if (end) close() else null
    }

    companion object {
        const val FRAME = 480 // 30 ms
        const val FRAME_MS = 30
        const val PAUSE_MS = 600
        const val LONG_PAUSE_MS = 2_000
        const val MIN_LINE_MS = 3_000
        const val SHORT_PAUSE_MS = 150
        const val SOFT_MAX_MS = 15_000
        const val HARD_MAX_MS = 25_000
        const val PRE_ROLL_MS = 240
        const val MIN_SPEECH_MS = 300
        const val MIN_LEVEL = 0.004f
    }
}

/** A growing FloatArray. */
internal class FloatBuilder {
    private var data = FloatArray(16_000)
    var size = 0
        private set

    fun add(values: FloatArray) {
        if (size + values.size > data.size) data = data.copyOf(max(data.size * 2, size + values.size))
        values.copyInto(data, size)
        size += values.size
    }

    fun toArray() = data.copyOf(size)
}
