package com.chencohensolution.transcriptor.speech

import android.media.MediaCodec
import android.media.MediaExtractor
import com.chencohensolution.transcriptor.recording.PcmConvert
import com.chencohensolution.transcriptor.recording.WavWriter
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * A lecture's recording (WAV; or FLAC or AAC through the system's decoder, at whatever rate and channels the decoder
 * gives) as the models take it, 16 kHz mono, from the start in pieces. It reads [fd], a file in the app's storage or in the lectures folder outside it, and
 * closes it.
 */
class RecordingAudio(private val fd: ParcelFileDescriptor, isWav: Boolean) : AudioSource, AutoCloseable {
    constructor(file: File) : this(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), file.name.endsWith(".wav"))

    /** Made for the decoder's output format (48 kHz stereo until it says otherwise: a WAV, or a FLAC from us). */
    private var downsampler = Downsampler()
    private var outRate = 48_000
    private var outChannels = 2
    private val pending = FloatBuilder()
    private val input = FileInputStream(fd.fileDescriptor)
    private val wav = if (isWav) input.channel.apply { position(WavWriter.HEADER_SIZE) } else null
    /** A WAV's bit depth (16, or 24 brought to 16 as it is read), from its header. */
    private val wavBits = wav?.let { bitsOf(it) } ?: 16
    private val narrow = ByteArray(64 * 1024)
    private val extractor = if (wav == null) MediaExtractor().apply { setDataSource(fd.fileDescriptor); selectTrack(0) } else null
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
            pending.add(downsampler.process(pcm, pcm.size - pcm.size % (outChannels * 2)))
        }
        if (pending.size == 0) return null
        val all = pending.drain()
        if (all.size > maxSamples) pending.add(all.copyOfRange(maxSamples, all.size))
        return all.copyOf(minOf(all.size, maxSamples))
    }

    /** The next PCM bytes (whole frames); null at the end. */
    private fun nextPcm(): ByteArray? {
        wav?.let { f ->
            if (wavBits == 24) {
                val n = f.read(ByteBuffer.wrap(buffer, 0, buffer.size / 6 * 6))
                if (n <= 0) return null
                val m = PcmConvert.pcm24To16(buffer, n - n % 6, narrow)
                return narrow.copyOf(m)
            }
            val n = f.read(ByteBuffer.wrap(buffer))
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
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // An AAC at 24 kHz mono, or an HE-AAC whose decoder found SBR and now gives twice the rate.
                val format = codec.outputFormat
                val rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                if (rate != outRate || channels != outChannels) {
                    outRate = rate
                    outChannels = channels
                    downsampler = Downsampler(rate, channels)
                }
            }
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
        private const val PRE_ROLL_MILLIS = 100L

        /** The bits per sample in a WAV header (at byte 34); the channel is left where it was. */
        private fun bitsOf(f: java.nio.channels.FileChannel): Int {
            val b = ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            f.read(b, 34)
            return if (b.position() == 2) b.getShort(0).toInt() else 16
        }

        /**
         * [startMillis] to [endMillis] of a recording, 16 kHz mono (from the nearest earlier frame, for FLAC); [open]
         * opens it (and it is closed after).
         */
        fun range(open: () -> ParcelFileDescriptor, isWav: Boolean, startMillis: Long, endMillis: Long): FloatArray {
            val want = ((endMillis - startMillis) * 16).toInt().coerceAtLeast(0)
            if (isWav) {
                open().use { fd ->
                    FileInputStream(fd.fileDescriptor).channel.use { f ->
                        val frame = 2 * bitsOf(f) / 8
                        f.position(WavWriter.HEADER_SIZE + startMillis * 48 * frame)
                        val bytes = ByteArray(want * 3 * frame)
                        val n = f.read(ByteBuffer.wrap(bytes)).coerceAtLeast(0)
                        if (frame == 6) {
                            val narrow = ByteArray(want * 3 * 4)
                            return Downsampler().process(narrow, PcmConvert.pcm24To16(bytes, n - n % 6, narrow))
                        }
                        return Downsampler().process(bytes, n - n % 4)
                    }
                }
            }
            return RecordingAudio(open(), isWav = false).use { audio ->
                // From a little before: an AAC frame decodes right only after the one before it (FLAC's need nothing).
                audio.extractor!!.seekTo((startMillis - PRE_ROLL_MILLIS).coerceAtLeast(0) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val skip = ((startMillis * 1000 - audio.extractor.sampleTime).coerceAtLeast(0) / 1000 * 16).toInt()
                val got = audio.read(skip + want) ?: return FloatArray(0)
                got.copyOfRange(minOf(skip, got.size), got.size)
            }
        }
    }

    override fun close() {
        codec?.run { stop(); release() }
        extractor?.release()
        input.close()
        fd.close()
    }
}
