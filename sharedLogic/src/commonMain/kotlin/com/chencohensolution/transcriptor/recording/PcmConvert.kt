package com.chencohensolution.transcriptor.recording

import kotlin.math.roundToInt

/**
 * Interleaved PCM between the microphone's floating point (-1..1), 16 bit and 24 bit (packed, little-endian): the
 * recorder reads floats for 24-bit recordings and hands 16 bits to live transcription and the meter.
 */
object PcmConvert {
    /** [count] floats of [src] to 16-bit bytes in [dst] (2 a sample); returns the bytes written. */
    fun floatTo16(src: FloatArray, count: Int, dst: ByteArray): Int {
        for (i in 0 until count) {
            val s = (src[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
            dst[2 * i] = s.toByte()
            dst[2 * i + 1] = (s shr 8).toByte()
        }
        return count * 2
    }

    /** [count] floats of [src] to 24-bit bytes in [dst] (3 a sample); returns the bytes written. */
    fun floatTo24(src: FloatArray, count: Int, dst: ByteArray): Int {
        for (i in 0 until count) {
            val s = (src[i] * 8_388_607f).roundToInt().coerceIn(-8_388_608, 8_388_607)
            dst[3 * i] = s.toByte()
            dst[3 * i + 1] = (s shr 8).toByte()
            dst[3 * i + 2] = (s shr 16).toByte()
        }
        return count * 3
    }

    /** The sample at [i] (in samples) of 24-bit [src]. */
    fun read24(src: ByteArray, i: Int): Int {
        val o = 3 * i
        return (src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8) or (src[o + 2].toInt() shl 16)
    }

    /** [size] bytes of 24-bit [src] to 16-bit bytes in [dst] (rounded); returns the bytes written. */
    fun pcm24To16(src: ByteArray, size: Int, dst: ByteArray): Int {
        val count = size / 3
        for (i in 0 until count) {
            val s = ((read24(src, i) + 128) shr 8).coerceIn(-32768, 32767)
            dst[2 * i] = s.toByte()
            dst[2 * i + 1] = (s shr 8).toByte()
        }
        return count * 2
    }

    /** [size] bytes of 24-bit [src] to floats in [dst]; returns the samples written. */
    fun pcm24ToFloat(src: ByteArray, size: Int, dst: FloatArray): Int {
        val count = size / 3
        for (i in 0 until count) dst[i] = read24(src, i) / 8_388_608f
        return count
    }
}
