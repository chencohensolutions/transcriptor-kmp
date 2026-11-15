package com.chencohensolution.transcriptor

import android.media.MediaPlayer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chencohensolution.transcriptor.recording.AacEncoder
import com.chencohensolution.transcriptor.recording.AacEncoders
import com.chencohensolution.transcriptor.settings.AacProfile
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.speech.RecordingAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Records ten seconds of a 440 Hz tone (as the microphone gives it, 48 kHz stereo) to AAC with every profile and on
 * every AAC encoder the device has, then reads it back as transcription does (all of it, and a range from the middle)
 * and as playback does (MediaPlayer's duration and a seek).
 */
@RunWith(AndroidJUnit4::class)
class AacRoundTripTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun record(settings: AacSettings, seconds: Int = 10): File {
        val file = File(dir, "test-${settings.profile}-${settings.sampleRate}-${settings.channels}-${settings.encoder}.aac")
        val encoder = AacEncoder(file, settings)
        val chunk = ByteArray(48_000 / 50 * 4) // 20 ms, as the recorder reads
        var frame = 0L
        repeat(seconds * 50) {
            for (f in 0 until chunk.size / 4) {
                val s = (sin(2 * PI * 440 * frame++ / 48_000) * 12_000).roundToInt()
                for (c in 0..1) { chunk[f * 4 + c * 2] = s.toByte(); chunk[f * 4 + c * 2 + 1] = (s shr 8).toByte() }
            }
            encoder.write(chunk, chunk.size)
            if (it == 100) encoder.checkpoint()
        }
        encoder.close()
        Log.i("AacRoundTrip", "$settings on ${encoder.encoderName}: ${file.length()} bytes")
        return file
    }

    private fun rms(x: FloatArray) = sqrt(x.map { it * it.toDouble() }.average())

    private fun check(settings: AacSettings) {
        val file = record(settings)
        val all = RecordingAudio(file).use { audio ->
            val out = ArrayList<Float>()
            while (true) out += (audio.read(16_000) ?: break).toList()
            out.toFloatArray()
        }
        // Ten seconds at 16 kHz, give or take the encoder's priming and last frame.
        assertTrue("$settings: ${all.size} samples", abs(all.size - 160_000) < 16_000 * 0.2)
        val middle = RecordingAudio.range({ android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY) }, false, 4_000, 5_000)
        assertTrue("$settings: range ${middle.size}", abs(middle.size - 16_000) < 400)
        val level = rms(middle.copyOfRange(800, middle.size))
        assertTrue("$settings: level $level", abs(level - 12_000.0 / 32768 / sqrt(2.0)) < 0.05)
        val player = MediaPlayer().apply { setDataSource(file.path); prepare() }
        assertTrue("$settings: duration ${player.duration}", abs(player.duration - 10_000) < 300)
        player.seekTo(6_000L, MediaPlayer.SEEK_CLOSEST)
        player.release()
    }

    @Test
    fun everyProfileOnTheAutomaticEncoder() {
        Log.i("AacRoundTrip", "encoders: ${AacEncoders.all}")
        check(AacSettings())
        check(AacSettings(sampleRate = 16_000, channels = 1, bitrateKbps = 32))
        check(AacSettings(sampleRate = 24_000, channels = 2, bitrateKbps = 96))
        check(AacSettings(profile = AacProfile.He, sampleRate = 48_000, channels = 1, bitrateKbps = 32))
        check(AacSettings(profile = AacProfile.HeV2, sampleRate = 48_000, channels = 2, bitrateKbps = 32))
    }

    @Test
    fun aWavIsEncodedToAacAfterwards() {
        val wav = File(dir, "after.wav")
        val writer = com.chencohensolution.transcriptor.recording.WavWriter(okio.FileSystem.SYSTEM, okio.Path.Companion.run { wav.path.toPath() })
        val chunk = ByteArray(48_000 / 50 * 4)
        var frame = 0L
        repeat(10 * 50) {
            for (f in 0 until chunk.size / 4) {
                val v = (sin(2 * PI * 440 * frame++ / 48_000) * 12_000).roundToInt()
                for (c in 0..1) { chunk[f * 4 + c * 2] = v.toByte(); chunk[f * 4 + c * 2 + 1] = (v shr 8).toByte() }
            }
            writer.write(chunk, chunk.size)
        }
        writer.close()
        val aac = File(dir, "after.aac")
        val frames = com.chencohensolution.transcriptor.recording.androidAacEncode.encode(
            okio.Path.Companion.run { wav.path.toPath() }, okio.Path.Companion.run { aac.path.toPath() }, AacSettings(), {},
        )
        assertEquals(480_000L, frames)
        val all = RecordingAudio(aac).use { audio -> var n = 0; while (true) n += (audio.read(16_000) ?: break).size; n }
        assertTrue("$all samples", abs(all - 160_000) < 3_200)
    }

    @Test
    fun everyEncoderTheDeviceHas() {
        for (encoder in AacEncoders.all) {
            check(AacSettings(encoder = encoder.name))
            assertEquals(encoder.name, AacEncoders.lastUsed)
        }
    }
}
