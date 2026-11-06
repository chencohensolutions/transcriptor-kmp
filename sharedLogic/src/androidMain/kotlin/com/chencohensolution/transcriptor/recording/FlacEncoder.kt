package com.chencohensolution.transcriptor.recording

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * PCM (16 bit, interleaved) to a .flac file, with Android's built-in FLAC encoder. The encoder hands out the stream's
 * header first (`fLaC` and STREAMINFO, maybe more metadata after it); this writes `fLaC` and STREAMINFO alone, marked
 * as the last metadata block, then the frames, and on [close] puts the total sample count into STREAMINFO.
 */
class FlacEncoder(
    file: File,
    private val sampleRate: Int = WavWriter.SAMPLE_RATE,
    private val channels: Int = WavWriter.CHANNELS,
    /** FLAC's compression level, 0 (fastest) to 8 (smallest). */
    level: Int = 5,
) : PcmSink {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
        configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, level.coerceIn(0, 8))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_SIZE)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private val header = java.io.ByteArrayOutputStream()
    private var headerWritten = false

    /** Frames (samples per channel) given to the encoder. */
    var frames = 0L
        private set

    override fun write(pcm: ByteArray, size: Int) {
        var offset = 0
        while (offset < size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) { drain(endOfStream = false); continue }
            val input = codec.getInputBuffer(index)!!
            input.clear()
            val n = minOf(size - offset, input.remaining())
            input.put(pcm, offset, n)
            codec.queueInputBuffer(index, 0, n, frames * 1_000_000 / sampleRate, 0)
            frames += n / (channels * 2)
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
        val packed = (sampleRate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or (15L shl 36)
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

/** [FlacEncode] with [FlacEncoder] at [level] (read for each file): the WAV's PCM in 64 KB pieces. */
fun androidFlacEncode(level: () -> Int = { 5 }) = FlacEncode { wav, flac, progress ->
    val wavFile = wav.toFile()
    val encoder = FlacEncoder(flac.toFile(), level = level())
    RandomAccessFile(wavFile, "r").use { input ->
        val total = input.length() - WavWriter.HEADER_SIZE
        input.seek(WavWriter.HEADER_SIZE)
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (done < total) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), total - done).toInt())
            if (n <= 0) break
            encoder.write(buffer, n - n % (WavWriter.CHANNELS * 2))
            done += n
            progress(done.toFloat() / total)
        }
    }
    encoder.close()
    encoder.frames
}
