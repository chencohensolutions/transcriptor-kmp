package com.chencohensolution.transcriptor.recording

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The microphone's PCM (48 kHz stereo 16 bit, interleaved) brought down to [sampleRate] (48, 24 or 16 kHz: a whole
 * fraction of 48) and [channels] (1 averages the two), 16 bit interleaved, for an encoder that takes less. Below 48 kHz
 * a low-pass filter (31 taps) runs first, so nothing folds back. Keeps its history between calls (whole frames).
 */
class PcmReducer(val sampleRate: Int, val channels: Int) {
    init {
        require(IN_RATE % sampleRate == 0) { "$sampleRate Hz isn't a whole fraction of $IN_RATE" }
        require(channels == 1 || channels == 2)
    }

    private val step = IN_RATE / sampleRate
    /** Nothing to do: 48 kHz stereo passes as it is. */
    val passThrough = step == 1 && channels == 2
    private val taps = if (step > 1) lowPass(0.45 * sampleRate / IN_RATE) else floatArrayOf(1f)
    private val history = Array(channels) { FloatArray(taps.size) }
    private var head = 0
    private var phase = 0
    private var out = ByteArray(0)

    /** The reduced PCM of [pcm]'s first [size] bytes; the returned array is reused by the next call, [Result.size] long. */
    fun process(pcm: ByteArray, size: Int): Result {
        if (passThrough) return Result(pcm, size)
        val frames = size / 4
        val need = (frames / step + 1) * channels * 2
        if (out.size < need) out = ByteArray(need)
        var n = 0
        for (f in 0 until frames) {
            val i = f * 4
            val left = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toFloat()
            val right = ((pcm[i + 2].toInt() and 0xFF) or (pcm[i + 3].toInt() shl 8)).toFloat()
            if (channels == 1) history[0][head] = (left + right) / 2 else { history[0][head] = left; history[1][head] = right }
            head = (head + 1) % taps.size
            if (phase == 0) {
                for (c in 0 until channels) {
                    var sum = 0f
                    var k = head
                    val h = history[c]
                    for (t in taps.indices) {
                        sum += taps[t] * h[k]
                        k = (k + 1) % taps.size
                    }
                    val s = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out[n++] = s.toByte()
                    out[n++] = (s shr 8).toByte()
                }
            }
            phase = (phase + 1) % step
        }
        return Result(out, n)
    }

    class Result(val pcm: ByteArray, val size: Int)

    private companion object {
        const val IN_RATE = 48_000

        fun lowPass(cutoff: Double): FloatArray {
            val n = 31
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
