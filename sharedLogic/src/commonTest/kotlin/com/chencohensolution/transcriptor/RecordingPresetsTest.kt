package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.recording.PcmConvert
import com.chencohensolution.transcriptor.recording.PcmReducer
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.EncodeAfter
import com.chencohensolution.transcriptor.settings.FlacFormat
import com.chencohensolution.transcriptor.settings.PowerPreset
import com.chencohensolution.transcriptor.settings.QualityPreset
import com.chencohensolution.transcriptor.settings.RecordingChoice
import com.chencohensolution.transcriptor.settings.SettingsStore
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingPresetsTest {
    private val owner = RecordingChoice(AudioFormat.WavThenAac, AacSettings(), FlacFormat(), EncodeAfter.Stop, EncodeAfter.Charging)

    @Test
    fun theOwnersSettingsAreNormalQualityAndCustomPower() {
        assertEquals(QualityPreset.Normal, owner.quality)
        assertEquals(PowerPreset.Custom, owner.power)
    }

    @Test
    fun highIsFlacStereoAndKeepsWavFirst() {
        val high = owner.with(QualityPreset.High)
        assertEquals(AudioFormat.WavThenFlac, high.audioFormat)
        assertEquals(FlacFormat(48_000, 2, 16), high.flac)
        assertEquals(QualityPreset.High, high.quality)
        assertEquals(QualityPreset.Custom, high.copy(flac = high.flac.copy(channels = 1)).quality)
    }

    @Test
    fun lowQualityIsMonoAacAndKeepsTheEncoder() {
        val low = owner.copy(audioFormat = AudioFormat.FlacLive, aac = AacSettings(encoder = "x")).with(QualityPreset.Low)
        assertEquals(AudioFormat.AacLive, low.audioFormat)
        assertEquals(1, low.aac.channels)
        assertEquals("x", low.aac.encoder)
        assertEquals(QualityPreset.Low, low.quality)
    }

    @Test
    fun powerPresetsSetTheTiming() {
        val low = owner.copy(audioFormat = AudioFormat.AacLive).with(PowerPreset.Low)
        assertEquals(AudioFormat.WavThenAac, low.audioFormat)
        assertEquals(EncodeAfter.Charging, low.encodeAfter)
        assertEquals(EncodeAfter.Charging, low.transcribeWhen)
        assertEquals(PowerPreset.Low, low.power)
        val normal = low.with(PowerPreset.Normal)
        assertEquals(EncodeAfter.Stop, normal.encodeAfter)
        assertEquals(EncodeAfter.Stop, normal.transcribeWhen)
        assertEquals(PowerPreset.Normal, normal.power)
        // Quality and power are independent.
        assertEquals(QualityPreset.Normal, normal.quality)
    }

    @Test
    fun storeKeepsTheChoice() {
        val fs = FakeFileSystem()
        val store = SettingsStore(fs, "/s".toPath())
        store.recordingChoice = owner.with(QualityPreset.High).with(PowerPreset.Low)
        val back = SettingsStore(fs, "/s".toPath()).recordingChoice
        assertEquals(QualityPreset.High, back.quality)
        assertEquals(PowerPreset.Low, back.power)
        assertEquals(16, store.flac.bitDepth)
    }

    @Test
    fun musicModeIsKeptAndWinsOverTheOthers() {
        val fs = FakeFileSystem()
        val store = SettingsStore(fs, "/s".toPath())
        store.recordMode = com.chencohensolution.transcriptor.settings.RecordMode.Music
        assertEquals(com.chencohensolution.transcriptor.settings.RecordMode.Music, SettingsStore(fs, "/s".toPath()).recordMode)
        store.recordMode = com.chencohensolution.transcriptor.settings.RecordMode.Live
        assertEquals(com.chencohensolution.transcriptor.settings.RecordMode.Live, store.recordMode)
    }

    @Test
    fun sizeWhileRecordingIsTheWavWhenItComesFirst() {
        assertEquals(691, owner.megabytesPerHourWhileRecording)
        assertEquals(115, owner.copy(audioFormat = AudioFormat.AacLive).megabytesPerHourWhileRecording)
        assertEquals(1036, owner.copy(audioFormat = AudioFormat.WavThenFlac, flac = FlacFormat(bitDepth = 24)).megabytesPerHourWhileRecording)
    }

    @Test
    fun soundIsSharedByBothCodecs() {
        val mono = owner.withSound(24_000, 1)
        assertEquals(24_000, mono.aac.sampleRate)
        assertEquals(24_000, mono.flac.sampleRate)
        assertEquals(1, mono.flac.channels)
        assertEquals(1, mono.channels)
        // HE v2 is stereo only: mono makes it HE; HE can't do 16 kHz: LC.
        val he2 = owner.copy(aac = AacSettings(com.chencohensolution.transcriptor.settings.AacProfile.HeV2, 48_000, 2, 32))
        assertEquals(com.chencohensolution.transcriptor.settings.AacProfile.He, he2.withSound(48_000, 1).aac.profile)
        assertEquals(com.chencohensolution.transcriptor.settings.AacProfile.Lc, he2.withSound(16_000, 1).aac.profile)
        // A preset moves the other codec too.
        assertEquals(1, owner.with(QualityPreset.Low).flac.channels)
        assertEquals(2, owner.with(QualityPreset.Low).with(QualityPreset.High).aac.channels)
    }

    @Test
    fun pcm24RoundTrips() {
        val floats = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 0.25f)
        val wide = ByteArray(18)
        PcmConvert.floatTo24(floats, 6, wide)
        val back = FloatArray(6)
        PcmConvert.pcm24ToFloat(wide, 18, back)
        floats.indices.forEach { assertEquals(floats[it], back[it], 1e-6f) }
        val narrow = ByteArray(12)
        assertEquals(12, PcmConvert.pcm24To16(wide, 18, narrow))
        assertEquals(16384, (narrow[2].toInt() and 0xFF) or (narrow[3].toInt() shl 8))
    }

    @Test
    fun reducerKeeps24Bits() {
        // 48 frames of a constant stereo signal at 24 bit, to 16 kHz mono: 16 samples of the average.
        val frames = 480
        val floats = FloatArray(frames * 2) { if (it % 2 == 0) 0.5f else 0.25f }
        val wide = ByteArray(frames * 6)
        PcmConvert.floatTo24(floats, floats.size, wide)
        val out = PcmReducer(16_000, 1, 24).process(wide, wide.size)
        assertEquals(frames / 3 * 3, out.size)
        // Past the filter's start, the average (0.375) at full 24-bit resolution.
        assertEquals((0.375 * 8_388_607).toInt().toDouble(), PcmConvert.read24(out.pcm, 100).toDouble(), 2.0)
    }
}
