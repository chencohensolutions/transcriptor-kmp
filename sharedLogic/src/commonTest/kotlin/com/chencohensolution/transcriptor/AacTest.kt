package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.recording.Adts
import com.chencohensolution.transcriptor.recording.PcmReducer
import com.chencohensolution.transcriptor.settings.AacProfile
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.speech.Downsampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AacTest {
    @Test
    fun settingsMoveToWhatTheProfileAllows() {
        val v2 = AacSettings(profile = AacProfile.HeV2, sampleRate = 16_000, channels = 1, bitrateKbps = 128).valid()
        assertEquals(24_000, v2.sampleRate)
        assertEquals(2, v2.channels)
        assertEquals(48, v2.bitrateKbps)
        val lc = AacSettings(profile = AacProfile.Lc, sampleRate = 44_100, channels = 1, bitrateKbps = 70).valid()
        assertEquals(48_000, lc.sampleRate)
        assertEquals(64, lc.bitrateKbps)
        assertEquals(AacSettings(), AacSettings().valid())
    }

    @Test
    fun adtsHeadersCarryTheCoreAndTheLength() {
        // AAC-LC, 48 kHz (index 3), stereo: AudioSpecificConfig 0x11 0x90.
        val header = Adts.withLength(Adts.headerFor(byteArrayOf(0x11, 0x90.toByte()), AacSettings()), 300)
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF1, header[1].toInt() and 0xFF)
        assertEquals(1, (header[2].toInt() shr 6) and 3) // LC
        assertEquals(3, (header[2].toInt() shr 2) and 0xF)
        val channels = ((header[2].toInt() and 1) shl 2) or ((header[3].toInt() shr 6) and 3)
        assertEquals(2, channels)
        val length = ((header[3].toInt() and 3) shl 11) or ((header[4].toInt() and 0xFF) shl 3) or ((header[5].toInt() and 0xFF) shr 5)
        assertEquals(307, length)
        // Explicit HE-AAC: type 5, core 24 kHz (index 6), stereo, extension 48 kHz, then LC.
        assertEquals(Triple(2, 6, 2), Adts.parse(byteArrayOf(0x2B, 0x11, 0x88.toByte(), 0x00)))
        assertNull(Adts.parse(byteArrayOf(0x11)))
        // No config: HE-AAC v2 at 48 kHz is a 24 kHz mono core.
        val fallback = Adts.headerFor(ByteArray(0), AacSettings(profile = AacProfile.HeV2, channels = 2, bitrateKbps = 32))
        assertEquals(6, (fallback[2].toInt() shr 2) and 0xF)
    }

    private fun tone(rate: Int, channels: Int, hz: Double, seconds: Double): ByteArray {
        val frames = (rate * seconds).toInt()
        val out = ByteArray(frames * channels * 2)
        for (f in 0 until frames) {
            val s = (sin(2 * PI * hz * f / rate) * 12_000).roundToInt()
            for (c in 0 until channels) { val i = (f * channels + c) * 2; out[i] = s.toByte(); out[i + 1] = (s shr 8).toByte() }
        }
        return out
    }

    private fun rms(x: FloatArray) = sqrt(x.drop(200).map { it * it }.average())

    @Test
    fun anyRateComesDownToSixteenKilohertz() {
        for ((rate, channels) in listOf(48_000 to 2, 24_000 to 1, 44_100 to 2, 16_000 to 1)) {
            val out = Downsampler(rate, channels).process(tone(rate, channels, 440.0, 1.0), (rate * channels * 2))
            assertTrue(abs(out.size - 16_000) <= 2, "$rate Hz gave ${out.size} samples")
            // A 440 Hz tone at 12000/32768 keeps its level (RMS of a sine: amplitude / sqrt 2).
            assertTrue(abs(rms(out) - 12_000.0 / 32768 / sqrt(2.0)) < 0.01, "$rate Hz: ${rms(out)}")
        }
        // Above 8 kHz is filtered out.
        assertTrue(rms(Downsampler(24_000, 1).process(tone(24_000, 1, 10_000.0, 1.0), 48_000)) < 0.02)
    }

    @Test
    fun theMicrophoneIsReducedForTheEncoder() {
        val pcm = tone(48_000, 2, 440.0, 1.0)
        val mono = PcmReducer(16_000, 1).process(pcm, pcm.size)
        assertEquals(16_000 * 2, mono.size)
        val stereo24 = PcmReducer(24_000, 2).process(pcm, pcm.size)
        assertEquals(24_000 * 4, stereo24.size)
        assertTrue(PcmReducer(48_000, 2).passThrough)
        assertEquals(48_000 * 2, PcmReducer(48_000, 1).process(pcm, pcm.size).size)
    }
}
