package com.chencohensolution.transcriptor.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Audio (16 bit, interleaved, [sampleRate] Hz, [channels] channels; the recording's 48 kHz stereo by default) to what
 * the models take (16 kHz mono, -1..1): the channels averaged, a low-pass filter below the new Nyquist frequency,
 * then 16 kHz picked out of it (every third sample from 48 kHz; between two samples, by straight line, when the rates
 * don't divide, as from an AAC at 24 or 44.1 kHz). 16 kHz goes through unfiltered. Keeps its history between calls, so
 * the buffers can be any size (whole frames).
 */
class Downsampler(private val sampleRate: Int = 48_000, private val channels: Int = 2) {
    private val filtered = sampleRate.toLong() != OUT_RATE
    private val taps = if (filtered) lowPass(sampleRate) else floatArrayOf(1f)
    // One more than the taps: the sample before the newest is filtered from the same history.
    private val history = FloatArray(taps.size + 1)
    private var head = 0
    /** Input samples seen, and outputs made: output k sits at input k * sampleRate / 16000. */
    private var seen = 0L
    private var made = 0L

    fun process(pcm: ByteArray, size: Int): FloatArray {
        val bytesPerFrame = channels * 2
        val frames = size / bytesPerFrame
        val out = FloatArray((frames.toLong() * OUT_RATE / sampleRate + 2).toInt())
        var n = 0
        for (f in 0 until frames) {
            var mix = 0
            val i = f * bytesPerFrame
            for (c in 0 until channels) {
                val j = i + c * 2
                mix += (pcm[j].toInt() and 0xFF) or (pcm[j + 1].toInt() shl 8)
            }
            history[head] = mix / (32768f * channels)
            head = (head + 1) % history.size
            val now = seen++
            // Outputs at input positions in (now - 1, now]: k * rate <= now * 16000.
            while (made * sampleRate <= now * OUT_RATE) {
                val after = made * sampleRate - (now - 1) * OUT_RATE // 1..16000: how far past the sample before
                val y = fir(0)
                out[n++] = if (after >= OUT_RATE) y else fir(1).let { it + (y - it) * after / OUT_RATE }
                made++
            }
        }
        return out.copyOf(n)
    }

    /** The filtered sample [back] samples before the newest. */
    private fun fir(back: Int): Float {
        var sum = 0f
        var k = (head - taps.size - back + 2 * history.size) % history.size
        for (t in taps.indices) {
            sum += taps[t] * history[k]
            k = (k + 1) % history.size
        }
        return sum
    }

    private companion object {
        const val OUT_RATE = 16_000L

        /** A windowed-sinc low-pass at 7 kHz for [rate], 63 taps. */
        fun lowPass(rate: Int): FloatArray {
            val n = 63
            val cutoff = 7_000.0 / rate
            val taps = DoubleArray(n) { i ->
                val m = i - (n - 1) / 2.0
                val sinc = if (m == 0.0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
                sinc * (0.54 - 0.46 * cos(2 * PI * i / (n - 1)))
            }
            val sum = taps.sum()
            return FloatArray(n) { (taps[it] / sum).toFloat() }
        }
    }
}
