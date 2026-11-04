package com.chencohensolution.transcriptor

import kotlin.math.PI
import kotlin.math.sin

/**
 * Test audio as the recorder gives it, 48 kHz stereo 16 bit: a tone (speech, as far as loudness goes; its pitch is
 * the "voice" for [PitchEmbedder]).
 */
fun tone(millis: Long, amplitude: Double = 0.3, hertz: Int = 300): ByteArray {
    val frames = (millis * 48).toInt()
    val out = ByteArray(frames * 4)
    for (f in 0 until frames) {
        val v = (sin(2 * PI * hertz * f / 48_000) * amplitude * Short.MAX_VALUE).toInt()
        for (c in 0..1) {
            out[f * 4 + c * 2] = v.toByte()
            out[f * 4 + c * 2 + 1] = (v shr 8).toByte()
        }
    }
    return out
}

fun silence(millis: Long) = ByteArray((millis * 48 * 4).toInt())

/** A voice print from pitch (zero crossings): a 300 Hz tone and an 800 Hz one are different speakers. */
class PitchEmbedder : com.chencohensolution.transcriptor.speech.VoiceEmbedder {
    var closed = false
    override fun embed(samples: FloatArray): FloatArray {
        var crossings = 0
        for (i in 1 until samples.size) if ((samples[i - 1] < 0) != (samples[i] < 0)) crossings++
        val hertz = crossings * 16_000.0 / samples.size / 2
        return if (hertz < 550) floatArrayOf(1f, 0.1f) else floatArrayOf(0.1f, 1f)
    }
    override fun close() { closed = true }
}
