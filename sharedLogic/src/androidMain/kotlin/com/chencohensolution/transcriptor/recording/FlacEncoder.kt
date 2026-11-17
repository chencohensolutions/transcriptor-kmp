package com.chencohensolution.transcriptor.recording

import android.media.MediaCodec
import android.media.MediaFormat
import com.chencohensolution.transcriptor.settings.FLAC_DEFAULT_LEVEL
import com.chencohensolution.transcriptor.settings.FLAC_LEVELS
import com.chencohensolution.transcriptor.settings.FlacFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * PCM (48 kHz stereo, interleaved, at [format]'s bit depth: 16, or 24 packed) to a .flac file at [format]'s rate and
 * channels ([PcmReducer] first), with Android's built-in FLAC encoder; 24 bits go in as floats, which the encoder
 * keeps as 24-bit FLAC. The encoder hands out the stream's
 * header first (`fLaC` and STREAMINFO, maybe more metadata after it); this writes `fLaC` and STREAMINFO alone, marked
 * as the last metadata block, then the frames, and on [close] puts the total sample count into STREAMINFO.
 * [level] is FLAC's compression level, 0 (fastest) to 8 (smallest).
 */
class FlacEncoder(
    file: File,
    private val level: Int = FLAC_DEFAULT_LEVEL,
    format: FlacFormat = FlacFormat(),
) : PcmSink {
    private val sampleRate = format.valid().sampleRate
    private val channels = format.valid().channels
    private val bits = format.valid().bitDepth
    private val reducer = PcmReducer(sampleRate, channels, bits)
    private var floats = FloatArray(0)
    private var floatBytes = ByteArray(0)
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
        configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, level.coerceIn(FLAC_LEVELS))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_SIZE)
                if (bits == 24) setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_FLOAT)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        start()
    }
    /** MediaCodec's name for the encoder ("c2.android.flac.encoder": Android's, on the CPU). */
    val encoderName: String = codec.name
    private val info = MediaCodec.BufferInfo()
    private val header = java.io.ByteArrayOutputStream()
    private var headerWritten = false

    /** Frames (samples per channel) given to the encoder, at its rate. */
    private var frames = 0L

    /** Frames taken in (48 kHz, before [PcmReducer]). */
    var framesIn = 0L
        private set

    override fun write(pcm: ByteArray, size: Int) {
        framesIn += size / (2 * bits / 8)
        val reduced = reducer.process(pcm, size)
        if (bits == 24) {
            if (floats.size < reduced.size / 3) { floats = FloatArray(reduced.size / 3); floatBytes = ByteArray(floats.size * 4) }
            val n = PcmConvert.pcm24ToFloat(reduced.pcm, reduced.size, floats)
            ByteBuffer.wrap(floatBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floats, 0, n)
            queue(floatBytes, n * 4, bytesPerFrame = channels * 4)
        } else {
            queue(reduced.pcm, reduced.size, bytesPerFrame = channels * 2)
        }
    }

    private fun queue(pcm: ByteArray, size: Int, bytesPerFrame: Int) {
        var offset = 0
        while (offset < size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) { drain(endOfStream = false); continue }
            val input = codec.getInputBuffer(index)!!
            input.clear()
            val room = input.remaining() - input.remaining() % bytesPerFrame
            val n = minOf(size - offset, room)
            input.put(pcm, offset, n)
            codec.queueInputBuffer(index, 0, n, frames * 1_000_000 / sampleRate, 0)
            frames += n / bytesPerFrame
            offset += n
            drain(endOfStream = false)
        }
    }

    /** FLAC frames are complete as they're written; nothing to fix after a crash but STREAMINFO's sample count. */
    override fun checkpoint() {
        out.fd.sync()
    }

    override fun close() {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, frames * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(endOfStream = false)
        }
        drain(endOfStream = true)
        codec.stop()
        codec.release()
        if (!headerWritten) writeHeader()
        writeTotalSamples()
        out.close()
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (endOfStream) continue else return }
            if (index < 0) continue // format or buffers changed
            val output = codec.getOutputBuffer(index)!!
            output.position(info.offset).limit(info.offset + info.size)
            val bytes = ByteArray(info.size).also(output::get)
            codec.releaseOutputBuffer(index, false)
            when {
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> header.write(bytes)
                bytes.isEmpty() -> {}
                !headerWritten && !isFrame(bytes) -> header.write(bytes) // metadata that came without the flag
                else -> {
                    if (!headerWritten) writeHeader()
                    out.write(bytes)
                }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /** A FLAC frame starts with the sync code 0xFFF8 (fixed block size) or 0xFFF9 (variable). */
    private fun isFrame(bytes: ByteArray) =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xFE) == 0xF8

    private fun writeHeader() {
        val got = header.toByteArray()
        val streamInfo = if (got.size >= 42 && String(got, 0, 4, Charsets.US_ASCII) == "fLaC" && got[4].toInt() and 0x7F == 0) {
            got.copyOfRange(8, 42)
        } else if (got.size >= 38 && got[0].toInt() and 0x7F == 0) {
            got.copyOfRange(4, 38) // the block without the marker
        } else {
            streamInfoOf()
        }
        out.seek(0)
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 34)) // STREAMINFO, the last metadata block, 34 bytes
        out.write(streamInfo)
        headerWritten = true
    }

    /** A STREAMINFO of our own, when the encoder gave none: any block size up to 65535, unknown frame sizes. */
    private fun streamInfoOf(): ByteArray {
        val b = ByteBuffer.allocate(34)
        b.putShort(16).putShort(65535.toShort())
        b.put(ByteArray(6))
        val packed = (sampleRate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((bits - 1).toLong() shl 36)
        b.putLong(packed)
        return b.array()
    }

    /** STREAMINFO's total samples: the low 36 bits of the 8 bytes at file offset 18 (after rate, channels, bits). */
    private fun writeTotalSamples() {
        out.seek(18)
        val packed = ByteArray(8).also { out.readFully(it) }
        val value = ByteBuffer.wrap(packed).long
        val updated = (value and 0xFFFFFFFFFL.inv()) or (frames and 0xFFFFFFFFFL)
        out.seek(18)
        out.write(ByteBuffer.allocate(8).putLong(updated).array())
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val INPUT_SIZE = 64 * 1024
    }
}

/** [FlacEncode] with [FlacEncoder]: the WAV's PCM (48 kHz stereo, 16 or 24 bit) in 64 KB pieces. */
val androidFlacEncode = FlacEncode { wav, flac, level, format, progress ->
    val wavFile = wav.toFile()
    val bits = WavWriter.read(okio.FileSystem.SYSTEM, wav).bitsPerSample
    val encoder = FlacEncoder(flac.toFile(), level, format.copy(bitDepth = bits))
    val frame = WavWriter.CHANNELS * bits / 8
    RandomAccessFile(wavFile, "r").use { input ->
        val total = input.length() - WavWriter.HEADER_SIZE
        input.seek(WavWriter.HEADER_SIZE)
        // 64 KB in whole frames (6 bytes a frame at 24 bits).
        val buffer = ByteArray(64 * 1024 / frame * frame)
        var done = 0L
        while (done < total) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), total - done).toInt())
            if (n <= 0) break
            encoder.write(buffer, n - n % frame)
            done += n
            progress(done.toFloat() / total)
        }
    }
    encoder.close()
    encoder.framesIn
}
