package com.chencohensolution.transcriptor

import kotlin.math.PI
import kotlin.math.sin

/** Test audio as the recorder gives it, 48 kHz stereo 16 bit: a 300 Hz tone (speech, as far as loudness goes). */
fun tone(millis: Long, amplitude: Double = 0.3): ByteArray {
    val frames = (millis * 48).toInt()
    val out = ByteArray(frames * 4)
    for (f in 0 until frames) {
        val v = (sin(2 * PI * 300 * f / 48_000) * amplitude * Short.MAX_VALUE).toInt()
        for (c in 0..1) {
            out[f * 4 + c * 2] = v.toByte()
            out[f * 4 + c * 2 + 1] = (v shr 8).toByte()
        }
    }
    return out
}

fun silence(millis: Long) = ByteArray((millis * 48 * 4).toInt())
