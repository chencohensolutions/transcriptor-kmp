package com.chencohensolution.transcriptor.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.chencohensolution.transcriptor.settings.Codec
import com.chencohensolution.transcriptor.settings.OtherCodec
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Which of the other codecs ([Codec.other]) this phone can encode, and with which of its encoders. */
object OtherEncoders {
    private const val XHE_PROFILE = 42 // MediaCodecInfo.CodecProfileLevel.AACObjectXHE

    /** The encoder for [codec], by name: hardware first; null when the phone has none. */
    fun encoderFor(codec: Codec): String? = all[codec]

    /** The other codecs the phone encodes. */
    val available: Set<Codec> get() = all.keys

    private val all: Map<Codec, String> by lazy {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        Codec.entries.filter { it.other }.mapNotNull { c ->
            val fits = infos.filter { info ->
                c.mime in info.supportedTypes.map(String::lowercase) && when (c) {
                    // xHE-AAC: an AAC encoder that takes the USAC profile; plain AAC isn't it.
                    Codec.Xhe -> info.getCapabilitiesForType(c.mime).profileLevels.any { it.profile == XHE_PROFILE }
                    else -> true
                } && !info.name.startsWith("OMX.")
            }
            (fits.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly } ?: fits.firstOrNull())?.let { c to it.name }
        }.toMap()
    }

    fun profileFor(codec: Codec) = if (codec == Codec.Xhe) XHE_PROFILE else null

    fun hardware(name: String?) = name != null &&
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.name == name && it.isHardwareAccelerated && !it.isSoftwareOnly }
}

/**
 * PCM (16 bit, interleaved, [inRate] and [inChannels]: the microphone's 48 kHz stereo, or a WAV's) to one of the other
 * codecs ([OtherCodec]): Opus into Ogg and xHE-AAC into MP4 (Android's muxer: whole only once closed), AMR-WB and AMR-NB
 * as their own files (`#!AMR-WB` / `#!AMR` and the frames: good up to the last frame after a crash). [PcmReducer] first.
 */
class OtherEncoder(
    file: File, private val spec: OtherCodec,
    inRate: Int = WavWriter.SAMPLE_RATE, inChannels: Int = WavWriter.CHANNELS,
) : PcmSink {
    private val reducer = PcmReducer(spec.sampleRate, spec.channels, 16, inRate, inChannels)
    private val bytesPerFrame = spec.channels * 2
    private val amr = spec.codec == Codec.AmrWb || spec.codec == Codec.AmrNb
    private val stream = if (amr) FileOutputStream(file) else null
    private val out = stream?.let { BufferedOutputStream(it, 4 * 1024) }?.apply {
        write((if (spec.codec == Codec.AmrWb) "#!AMR-WB\n" else "#!AMR\n").toByteArray(Charsets.US_ASCII))
    }
    private val muxer = if (amr) null else MediaMuxer(
        file.path, if (spec.codec == Codec.Opus) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    )
    private var track = -1
    private val codec: MediaCodec = run {
        val name = OtherEncoders.encoderFor(spec.codec) ?: error("No ${spec.codec.key} encoder on this phone")
        MediaCodec.createByCodecName(name).apply {
            configure(
                MediaFormat.createAudioFormat(spec.codec.mime, spec.sampleRate, spec.channels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_SIZE)
                    OtherEncoders.profileFor(spec.codec)?.let { setInteger(MediaFormat.KEY_AAC_PROFILE, it) }
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            start()
        }
    }
    val encoderName: String = codec.name
    private val info = MediaCodec.BufferInfo()
    private var frames = 0L

    /** Frames taken in (before [PcmReducer]). */
    var framesIn = 0L
        private set
    private val inBytesPerFrame = inChannels * 2

    override fun write(pcm: ByteArray, size: Int) {
        framesIn += size / inBytesPerFrame
        val reduced = reducer.process(pcm, size)
        var offset = 0
        while (offset < reduced.size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) { drain(endOfStream = false); continue }
            val input = codec.getInputBuffer(index)!!
            input.clear()
            val n = minOf(reduced.size - offset, input.remaining() - input.remaining() % bytesPerFrame)
            input.put(reduced.pcm, offset, n)
            codec.queueInputBuffer(index, 0, n, frames * 1_000_000 / spec.sampleRate, 0)
            frames += n / bytesPerFrame
            offset += n
            drain(endOfStream = false)
        }
    }

    override fun checkpoint() {
        out?.flush()
        stream?.fd?.sync()
    }

    override fun close() {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, frames * 1_000_000 / spec.sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(endOfStream = false)
        }
        drain(endOfStream = true)
        codec.stop()
        codec.release()
        out?.run { flush(); stream!!.fd.sync(); close() }
        muxer?.run { if (track >= 0) stop(); release() }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (endOfStream) continue else return }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer?.let { m -> if (track < 0) { track = m.addTrack(codec.outputFormat); m.start() } }
                continue
            }
            if (index < 0) continue
            val output = codec.getOutputBuffer(index)!!
            output.position(info.offset).limit(info.offset + info.size)
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!config && info.size > 0) {
                if (out != null) {
                    val bytes = ByteArray(info.size).also(output::get)
                    out.write(bytes)
                } else if (track >= 0) {
                    muxer!!.writeSampleData(track, output, info)
                }
            }
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(index, false)
            if (eos) return
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val INPUT_SIZE = 16 * 1024
    }
}

/** [OtherEncode] with [OtherEncoder]: the WAV's PCM (a 24-bit one brought to 16) in 64 KB pieces; returns the WAV frames taken in. */
val androidOtherEncode = OtherEncode { wav, out, spec, progress ->
    val info = WavWriter.read(okio.FileSystem.SYSTEM, wav)
    val encoder = OtherEncoder(out.toFile(), spec.copy(
        // Not finer than the WAV (its rate a whole multiple of the codec's, as recorded for it).
        sampleRate = if (info.sampleRate % spec.sampleRate == 0) spec.sampleRate else info.sampleRate,
        channels = minOf(spec.channels, info.channels),
    ), info.sampleRate, info.channels)
    val frame = info.blockAlign
    val narrow = if (info.bitsPerSample == 24) ByteArray(64 * 1024) else null
    var frames = 0L
    RandomAccessFile(wav.toFile(), "r").use { input ->
        val total = input.length() - WavWriter.HEADER_SIZE
        input.seek(WavWriter.HEADER_SIZE)
        val buffer = ByteArray(64 * 1024 / frame * frame)
        var done = 0L
        while (done < total) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), total - done).toInt())
            if (n <= 0) break
            val whole = n - n % frame
            if (narrow != null) encoder.write(narrow, PcmConvert.pcm24To16(buffer, whole, narrow)) else encoder.write(buffer, whole)
            frames += whole / frame
            done += n
            progress(done.toFloat() / total)
        }
    }
    encoder.close()
    frames
}
