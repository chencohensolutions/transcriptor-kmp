package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.recording.METER_BANDS
import com.chencohensolution.transcriptor.recording.METER_HISTORY
import com.chencohensolution.transcriptor.recording.MeterAnalyzer
import com.chencohensolution.transcriptor.recording.bandCenter
import com.chencohensolution.transcriptor.settings.LevelMeterStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelMeterTest {
    /** 20 ms of 48 kHz stereo 16 bit: a sine of [hz] at [left] and [right] of full scale. */
    private fun buffer(hz: Float, left: Float, right: Float, start: Int = 0): ByteArray {
        val frames = 960
        val out = ByteArray(frames * 4)
        for (n in 0 until frames) {
            val s = sin(2 * PI * hz * (start + n) / 48_000)
            put(out, n * 4, (s * left * Short.MAX_VALUE).roundToInt())
            put(out, n * 4 + 2, (s * right * Short.MAX_VALUE).roundToInt())
        }
        return out
    }

    private fun put(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte()
        b[i + 1] = (v shr 8).toByte()
    }

    @Test
    fun eachChannelHasItsOwnLevel() {
        val meter = MeterAnalyzer()
        val pcm = buffer(440f, 0.5f, 0.05f)
        val state = meter.read(pcm, pcm.size)
        assertTrue(state.now.left > state.now.right + 0.2f, "${state.now}")
        assertTrue(state.stereo)
        assertEquals(state.now.left, state.now.level)
    }

    @Test
    fun theSameSoundInBothChannelsIsMono() {
        val meter = MeterAnalyzer()
        val pcm = buffer(440f, 0.3f, 0.3f)
        assertFalse(meter.read(pcm, pcm.size).stereo)
    }

    @Test
    fun peaksHoldThenFall() {
        val meter = MeterAnalyzer()
        val loud = buffer(440f, 0.8f, 0.8f)
        val quiet = buffer(440f, 0.001f, 0.001f)
        val peak = meter.read(loud, loud.size).now.leftPeak
        val after = meter.read(quiet, quiet.size).now
        assertTrue(after.leftPeak > after.left && after.leftPeak < peak)
    }

    @Test
    fun historyKeepsTheLastEightSeconds() {
        val meter = MeterAnalyzer()
        val pcm = buffer(440f, 0.3f, 0.2f)
        repeat(METER_HISTORY + 15) { meter.read(pcm, pcm.size) }
        assertEquals(METER_HISTORY, meter.state.history.size)
        assertEquals(0f, meter.silence().now.left)
        assertEquals(METER_HISTORY, meter.state.history.size)
    }

    @Test
    fun noBandsUnlessWanted() {
        val meter = MeterAnalyzer()
        val pcm = buffer(1000f, 0.3f, 0.3f)
        repeat(5) { meter.feed(pcm, pcm.size); meter.read(pcm, pcm.size) }
        assertTrue(meter.state.bands.isEmpty())
    }

    @Test
    fun aToneLightsItsBand() {
        val meter = MeterAnalyzer().apply { wantBands = true }
        for (tone in listOf(200f, 1000f, 4000f)) {
            var start = 0
            repeat(4) {
                val pcm = buffer(tone, 0.3f, 0.3f, start)
                start += 960
                meter.feed(pcm, pcm.size)
            }
            val pcm = buffer(tone, 0.3f, 0.3f, start)
            val bands = meter.read(pcm, pcm.size).bands
            assertEquals(METER_BANDS, bands.size)
            val loudest = bands.indices.maxBy { bands[it] }
            val expected = (0 until METER_BANDS).minBy { abs(ln(bandCenter(it) / tone)) }
            assertTrue(abs(loudest - expected) <= 1, "$tone Hz: band $loudest, expected $expected ($bands)")
            assertTrue(bands[loudest] > 0.6f, "$tone Hz loud: ${bands[loudest]}")
            assertTrue(bands[if (expected > 12) 0 else METER_BANDS - 1] < 0.35f, "far bands quiet for $tone Hz: $bands")
        }
    }

    @Test
    fun unknownStylesReadAsBars() {
        assertEquals(LevelMeterStyle.Bars, LevelMeterStyle.of("waveform-3d"))
        assertEquals(LevelMeterStyle.Spectrogram, LevelMeterStyle.of("spectrogram"))
        assertTrue(LevelMeterStyle.Spectrum.needsBands && !LevelMeterStyle.Strip.needsBands)
    }
}
