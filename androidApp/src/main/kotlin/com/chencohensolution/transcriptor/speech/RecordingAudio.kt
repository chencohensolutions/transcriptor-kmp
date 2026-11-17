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
    /** A WAV's bit depth (16, or 24 brought to 16 as it is read), rate and channels, from its header. */
    private val wavFormat = wav?.let { formatOf(it) }
    private val wavBits = wavFormat?.bits ?: 16

    init {
        wavFormat?.let { f ->
            if (f.rate != outRate || f.channels != outChannels) {
                outRate = f.rate
                outChannels = f.channels
                downsampler = Downsampler(f.rate, f.channels)
            }
        }
    }
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
                val frame = 3 * outChannels
                val n = f.read(ByteBuffer.wrap(buffer, 0, buffer.size / frame * frame))
                if (n <= 0) return null
                val m = PcmConvert.pcm24To16(buffer, n - n % frame, narrow)
                return narrow.copyOf(m)
            }
            val n = f.read(ByteBuffer.wrap(buffer))
            return if (n <= 0) null else buffer.copyOf(n - n % (2 * outChannels))
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

        private class WavFormat(val rate: Int, val channels: Int, val bits: Int) {
            val blockAlign get() = channels * bits / 8
        }

        /** A WAV header's channels (byte 22), rate (24) and bits per sample (34); the channel is left where it was. */
        private fun formatOf(f: java.nio.channels.FileChannel): WavFormat {
            val b = ByteBuffer.allocate(14).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            f.read(b, 22)
            if (b.position() < 14) return WavFormat(48_000, 2, 16)
            return WavFormat(b.getInt(2), b.getShort(0).toInt(), b.getShort(12).toInt())
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
                        val w = formatOf(f)
                        val frame = w.blockAlign
                        f.position(WavWriter.HEADER_SIZE + startMillis * w.rate / 1000 * frame)
                        // [want] samples at 16 kHz: want * rate / 16000 frames of the WAV.
                        val bytes = ByteArray((want.toLong() * w.rate / 16_000 * frame).toInt())
                        val n = f.read(ByteBuffer.wrap(bytes)).coerceAtLeast(0)
                        val down = Downsampler(w.rate, w.channels)
                        if (w.bits == 24) {
                            val narrow = ByteArray(bytes.size / 3 * 2)
                            return down.process(narrow, PcmConvert.pcm24To16(bytes, n - n % frame, narrow))
                        }
                        return down.process(bytes, n - n % frame)
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
