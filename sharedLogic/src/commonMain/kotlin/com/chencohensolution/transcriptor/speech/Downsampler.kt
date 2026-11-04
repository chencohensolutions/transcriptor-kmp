package com.chencohensolution.transcriptor.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The recording (48 kHz, stereo, 16 bit, interleaved) to what the models take (16 kHz mono, -1..1): the channels
 * averaged, a low-pass filter below the new Nyquist frequency, then every third sample. Keeps its history between
 * calls, so the buffers can be any size (whole frames).
 */
class Downsampler {
    private val taps = lowPass()
    private val history = FloatArray(taps.size)
    private var head = 0
    private var phase = 0

    fun process(pcm: ByteArray, size: Int): FloatArray {
        val frames = size / 4
        val out = FloatArray((frames + phase) / 3 + 1)
        var n = 0
        for (f in 0 until frames) {
            val i = f * 4
            val left = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val right = (pcm[i + 2].toInt() and 0xFF) or (pcm[i + 3].toInt() shl 8)
            history[head] = (left + right) / 65536f
            head = (head + 1) % history.size
            if (phase == 0) {
                var sum = 0f
                var k = head
                for (t in taps.indices) {
                    sum += taps[t] * history[k]
                    k = (k + 1) % history.size
                }
                out[n++] = sum
            }
            phase = (phase + 1) % 3
        }
        return out.copyOf(n)
    }

    private companion object {
        /** A windowed-sinc low-pass at 7 kHz for 48 kHz, 63 taps. */
        fun lowPass(): FloatArray {
            val n = 63
            val cutoff = 7_000.0 / 48_000
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
