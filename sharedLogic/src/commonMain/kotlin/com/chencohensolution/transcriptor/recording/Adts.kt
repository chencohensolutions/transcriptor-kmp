package com.chencohensolution.transcriptor.recording

import com.chencohensolution.transcriptor.settings.AacProfile
import com.chencohensolution.transcriptor.settings.AacSettings

/**
 * ADTS headers for raw AAC frames: 7 bytes, no CRC. HE-AAC is written as its AAC-LC core (half the rate; v2 a mono
 * core) and decoders find SBR and parametric stereo in the frames themselves ("implicit signalling"), as players do
 * with any HE-AAC .aac file.
 */
object Adts {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /**
     * The fixed part of the header (length 0) from the encoder's AudioSpecificConfig [asc]; from [settings] when the
     * config is missing or one this doesn't read.
     */
    fun headerFor(asc: ByteArray, settings: AacSettings): ByteArray {
        val (objectType, rateIndex, channelConfig) = parse(asc) ?: fallback(settings)
        val profile = (objectType - 1).coerceIn(0, 3)
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((profile shl 6) or (rateIndex shl 2) or (channelConfig shr 2)).toByte(),
            ((channelConfig and 3) shl 6).toByte(), 0, 0x1F, 0xFC.toByte(),
        )
    }

    /** [header] with its frame length set for a payload of [payload] bytes (a new array). */
    fun withLength(header: ByteArray, payload: Int): ByteArray {
        val length = payload + 7
        return header.copyOf().apply {
            this[3] = ((this[3].toInt() and 0xC0) or (length shr 11)).toByte()
            this[4] = (length shr 3).toByte()
            this[5] = (((length and 7) shl 5) or 0x1F).toByte()
        }
    }

    /** The core object type, sample-rate index and channel configuration in an AudioSpecificConfig, or null. */
    fun parse(asc: ByteArray): Triple<Int, Int, Int>? {
        if (asc.size < 2) return null
        var bit = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = bit / 8
                if (byte >= asc.size) return -1
                v = (v shl 1) or ((asc[byte].toInt() shr (7 - bit % 8)) and 1)
                bit++
            }
            return v
        }
        var type = read(5)
        val rate = read(4)
        val channels = read(4)
        if (rate == 15 || rate < 0 || channels < 0) return null
        if (type == 5 || type == 29) { // explicit SBR (and PS): the core comes after the extension's rate
            if (read(4) < 0) return null
            type = read(5)
        }
        if (type !in 1..4) return null
        return Triple(type, rate, if (channels == 0) 1 else channels)
    }

    private fun fallback(s: AacSettings): Triple<Int, Int, Int> {
        val core = if (s.profile == AacProfile.Lc) s.sampleRate else s.sampleRate / 2
        val index = RATES.indexOf(core).takeIf { it >= 0 } ?: 3
        return Triple(2, index, if (s.profile == AacProfile.HeV2) 1 else s.channels)
    }
}
