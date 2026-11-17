package com.chencohensolution.transcriptor

import android.media.MediaPlayer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chencohensolution.transcriptor.recording.FlacEncoder
import com.chencohensolution.transcriptor.recording.PcmConvert
import com.chencohensolution.transcriptor.recording.WavWriter
import com.chencohensolution.transcriptor.recording.androidAacEncode
import com.chencohensolution.transcriptor.recording.androidFlacEncode
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.settings.FlacFormat
import com.chencohensolution.transcriptor.speech.RecordingAudio
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Five seconds of a 440 Hz tone, as the recorder gives it (48 kHz stereo; 24 bit packed from floats), to FLAC in each
 * rate, channel count and bit depth; and through a 24-bit WAV to FLAC and AAC. Each is checked for the bit depth, rate
 * and channels in its STREAMINFO, read back as transcription reads it, and opened by MediaPlayer.
 */
@RunWith(AndroidJUnit4::class)
class FlacFormatRoundTripTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val seconds = 5

    /** 20 ms chunks of the tone at [bits]. */
    private fun chunks(bits: Int, each: (ByteArray, Int) -> Unit) {
        val floats = FloatArray(48_000 / 50 * 2)
        val out = ByteArray(floats.size * 3)
        var frame = 0L
        repeat(seconds * 50) {
            for (f in 0 until floats.size / 2) {
                val s = (sin(2 * PI * 440 * frame++ / 48_000) * 0.4).toFloat()
                floats[2 * f] = s; floats[2 * f + 1] = s
            }
            val n = if (bits == 24) PcmConvert.floatTo24(floats, floats.size, out) else PcmConvert.floatTo16(floats, floats.size, out)
            each(out, n)
        }
    }

    /** STREAMINFO's rate, channels, bits and total samples. */
    private fun streamInfo(file: File): List<Long> = RandomAccessFile(file, "r").use { f ->
        f.seek(18)
        val v = f.readLong()
        listOf(v ushr 44, ((v ushr 41) and 7) + 1, ((v ushr 36) and 31) + 1, v and 0xFFFFFFFFFL)
    }

    private fun readBack(file: File): FloatArray = RecordingAudio(file).use { audio ->
        val out = ArrayList<Float>()
        while (true) out += (audio.read(16_000) ?: break).toList()
        out.toFloatArray()
    }

    private fun checkAudio(file: File, what: String) {
        val all = readBack(file)
        // AAC adds its priming and a last whole frame (as in AacRoundTripTest): a fifth of a second.
        assertTrue("$what: ${all.size} samples", abs(all.size - seconds * 16_000) < 16_000 * 0.2)
        val rms = sqrt(all.copyOfRange(16_000, 32_000).map { it * it.toDouble() }.average())
        // A sine at 0.4: RMS about 0.28. xHE-AAC's decoder evens out loudness (to -24 LKFS, part of the codec), so quieter.
        val xhe = what.startsWith("Xhe")
        assertTrue("$what: rms $rms", rms in (if (xhe) 0.05 else 0.2)..0.36)
        val player = MediaPlayer().apply { setDataSource(file.path); prepare() }
        val duration = player.duration
        player.release()
        assertTrue("$what: duration $duration", abs(duration - seconds * 1000) < 200)
    }

    @Test
    fun everyFormatLive() {
        for (bits in FlacFormat.DEPTHS) for (rate in FlacFormat.RATES) for (channels in 1..2) {
            val format = FlacFormat(rate, channels, bits)
            val file = File(dir, "test-$rate-$channels-$bits.flac")
            val encoder = FlacEncoder(file, 5, format)
            chunks(bits) { b, n -> encoder.write(b, n) }
            encoder.close()
            val info = streamInfo(file)
            Log.i("FlacFormat", "$format: ${file.length()} bytes, STREAMINFO $info")
            assertEquals("$format", listOf(rate.toLong(), channels.toLong(), bits.toLong(), seconds * rate.toLong()), info)
            assertEquals(seconds * 48_000L, encoder.framesIn)
            checkAudio(file, "$format")
        }
    }

    @Test
    fun a24BitWavToFlacAndAac() {
        val wav = File(dir, "test24.wav")
        val writer = WavWriter(FileSystem.SYSTEM, wav.toOkioPath(), bits = 24)
        chunks(24) { b, n -> writer.write(b, n) }
        writer.close()
        assertEquals(24, WavWriter.read(FileSystem.SYSTEM, wav.toOkioPath()).bitsPerSample)
        checkAudio(wav, "24-bit WAV")

        val flac = File(dir, "test24-from-wav.flac")
        val frames = androidFlacEncode.encode(wav.toOkioPath(), flac.toOkioPath(), 8, FlacFormat(24_000, 1, 16)) {}
        assertEquals(seconds * 48_000L, frames)
        // The WAV's bit depth wins over the setting's.
        assertEquals(listOf(24_000L, 1L, 24L, seconds * 24_000L), streamInfo(flac))
        checkAudio(flac, "FLAC from 24-bit WAV")

        val aac = File(dir, "test24-from-wav.aac")
        assertEquals(seconds * 48_000L, androidAacEncode.encode(wav.toOkioPath(), aac.toOkioPath(), AacSettings()) {})
        checkAudio(aac, "AAC from 24-bit WAV")
    }

    /** A WAV at the shared sound (24 kHz mono, 16 and 24 bit) reads back, and encodes to FLAC and AAC at its own rate. */
    @Test
    fun aReducedWavReadsAndEncodes() {
        for (bits in FlacFormat.DEPTHS) {
            val wav = File(dir, "test-24k-mono-$bits.wav")
            val sink = com.chencohensolution.transcriptor.recording.ReducingSink(
                com.chencohensolution.transcriptor.recording.PcmReducer(24_000, 1, bits),
                WavWriter(FileSystem.SYSTEM, wav.toOkioPath(), 24_000, 1, bits),
            )
            chunks(bits) { b, n -> sink.write(b, n) }
            sink.close()
            val info = WavWriter.read(FileSystem.SYSTEM, wav.toOkioPath())
            assertEquals(listOf(24_000, 1, bits), listOf(info.sampleRate, info.channels, info.bitsPerSample))
            assertEquals(seconds * 24_000L, info.frames)
            checkAudio(wav, "24 kHz mono $bits-bit WAV")
            val part = RecordingAudio.range({ android.os.ParcelFileDescriptor.open(wav, android.os.ParcelFileDescriptor.MODE_READ_ONLY) }, true, 1_000, 2_000)
            assertTrue("range ${part.size}", abs(part.size - 16_000) < 100)

            val flac = File(dir, "test-24k-mono-$bits.flac")
            assertEquals(seconds * 24_000L, androidFlacEncode.encode(wav.toOkioPath(), flac.toOkioPath(), 5, FlacFormat(48_000, 2, 16)) {})
            assertEquals(listOf(24_000L, 1L, bits.toLong(), seconds * 24_000L), streamInfo(flac))
            checkAudio(flac, "FLAC from 24 kHz mono $bits-bit WAV")

            val aac = File(dir, "test-24k-mono-$bits.aac")
            assertEquals(seconds * 24_000L, androidAacEncode.encode(wav.toOkioPath(), aac.toOkioPath(), AacSettings(sampleRate = 24_000, channels = 1, bitrateKbps = 48)) {})
            checkAudio(aac, "AAC from 24 kHz mono $bits-bit WAV")
        }
    }

    /** Every other codec the device encodes (Opus, xHE-AAC, AMR-WB, AMR-NB): live from the mic's PCM, and from a WAV. */
    @Test
    fun theOtherCodecs() {
        val available = com.chencohensolution.transcriptor.recording.OtherEncoders.available
        Log.i("FlacFormat", "other codecs here: $available")
        assertTrue("Opus at least", com.chencohensolution.transcriptor.settings.Codec.Opus in available)
        for (codec in available) {
            val profile = com.chencohensolution.transcriptor.settings.QualityProfile.Normal.defaults
            val spec = profile.other(codec)!!
            val file = File(dir, "test-other." + codec.file.substringAfterLast('.'))
            val encoder = com.chencohensolution.transcriptor.recording.OtherEncoder(file, spec)
            chunks(16) { b, n -> encoder.write(b, n) }
            encoder.close()
            Log.i("FlacFormat", "$spec on ${encoder.encoderName}: ${file.length()} bytes")
            assertEquals(seconds * 48_000L, encoder.framesIn)
            checkAudio(file, "$codec live")

            val wav = File(dir, "test-other-${codec.key}.wav")
            val sink = com.chencohensolution.transcriptor.recording.ReducingSink(
                com.chencohensolution.transcriptor.recording.PcmReducer(spec.sampleRate, spec.channels, 16),
                WavWriter(FileSystem.SYSTEM, wav.toOkioPath(), spec.sampleRate, spec.channels, 16),
            )
            chunks(16) { b, n -> sink.write(b, n) }
            sink.close()
            val out = File(dir, "test-other-from-wav." + codec.file.substringAfterLast('.'))
            assertEquals(seconds.toLong() * spec.sampleRate, com.chencohensolution.transcriptor.recording.androidOtherEncode.encode(wav.toOkioPath(), out.toOkioPath(), spec) {})
            checkAudio(out, "$codec from WAV")
        }
    }
}
