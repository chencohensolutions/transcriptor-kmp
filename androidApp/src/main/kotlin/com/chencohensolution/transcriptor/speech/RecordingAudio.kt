package com.chencohensolution.transcriptor.speech

import android.media.MediaCodec
import android.media.MediaExtractor
import com.chencohensolution.transcriptor.recording.WavWriter
import java.io.File
import java.io.RandomAccessFile

/**
 * A lecture's recording (WAV, or FLAC through the system's decoder; 48 kHz stereo 16 bit) as the models take it, 16 kHz
 * mono, from the start in pieces.
 */
class RecordingAudio(file: File) : AudioSource, AutoCloseable {
    private val downsampler = Downsampler()
    private val pending = FloatBuilder()
    private val wav = if (file.name.endsWith(".wav")) RandomAccessFile(file, "r").apply { seek(WavWriter.HEADER_SIZE) } else null
    private val extractor = if (wav == null) MediaExtractor().apply { setDataSource(file.path); selectTrack(0) } else null
    private val codec = extractor?.let { e ->
        val format = e.getTrackFormat(0)
        MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
    }
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var done = false
    private val buffer = ByteArray(64 * 1024)

    override fun read(maxSamples: Int): FloatArray? {
        while (pending.size < maxSamples && !done) {
            val pcm = nextPcm() ?: run { done = true; null } ?: break
            pending.add(downsampler.process(pcm, pcm.size))
        }
        if (pending.size == 0) return null
        val all = pending.drain()
        if (all.size > maxSamples) pending.add(all.copyOfRange(maxSamples, all.size))
        return all.copyOf(minOf(all.size, maxSamples))
    }

    /** The next PCM bytes (whole frames); null at the end. */
    private fun nextPcm(): ByteArray? {
        wav?.let { f ->
            val n = f.read(buffer)
            return if (n <= 0) null else buffer.copyOf(n - n % 4)
        }
        val codec = codec!!
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index)!!
                    val n = extractor!!.readSampleData(input, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            if (out >= 0) {
                val bytes = ByteArray(info.size)
                codec.getOutputBuffer(out)!!.apply { position(info.offset); limit(info.offset + info.size) }.get(bytes)
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && bytes.isEmpty()) return null
                if (bytes.isNotEmpty()) return bytes
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
            }
        }
    }

    companion object {
        /** [startMillis] to [endMillis] of a recording, 16 kHz mono (from the nearest earlier frame, for FLAC). */
        fun range(file: File, startMillis: Long, endMillis: Long): FloatArray {
            val want = ((endMillis - startMillis) * 16).toInt().coerceAtLeast(0)
            if (file.name.endsWith(".wav")) {
                RandomAccessFile(file, "r").use { f ->
                    f.seek(WavWriter.HEADER_SIZE + startMillis * 48 * 4)
                    val bytes = ByteArray(want * 3 * 4)
                    val n = f.read(bytes).coerceAtLeast(0)
                    return Downsampler().process(bytes, n - n % 4)
                }
            }
            return RecordingAudio(file).use { audio ->
                audio.extractor!!.seekTo(startMillis * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val skip = ((startMillis * 1000 - audio.extractor.sampleTime).coerceAtLeast(0) / 1000 * 16).toInt()
                val got = audio.read(skip + want) ?: return FloatArray(0)
                got.copyOfRange(minOf(skip, got.size), got.size)
            }
        }
    }

    override fun close() {
        wav?.close()
        codec?.run { stop(); release() }
        extractor?.release()
    }
}
