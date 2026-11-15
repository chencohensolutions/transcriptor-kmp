package com.chencohensolution.transcriptor.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.chencohensolution.transcriptor.settings.AacProfile
import com.chencohensolution.transcriptor.settings.AacSettings
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** An AAC encoder this phone has: its MediaCodec name, and whether it runs on dedicated hardware (a DSP) or the CPU. */
data class AacEncoderInfo(
    val name: String,
    val hardware: Boolean,
    /** The phone maker's or chip maker's, not Android's own. */
    val vendor: Boolean,
    val profiles: Set<AacProfile>,
    val bitrates: IntRange,
)

/** The phone's AAC encoders, and which one "automatic" picks. */
object AacEncoders {
    const val SOFTWARE = "c2.android.aac.encoder"

    val all: List<AacEncoderInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MediaFormat.MIMETYPE_AUDIO_AAC in it.supportedTypes.map(String::lowercase) }
            // Left out: xHE-AAC's encoder (USAC, which the ADTS file can't carry) and the old OMX copy of Android's.
            .filter { "xheaac" !in it.name.lowercase() && !it.name.startsWith("OMX.google.") }
            .map { info ->
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val profiles = caps.profileLevels.mapNotNull { profileOf(it.profile) }.toSet().ifEmpty { setOf(AacProfile.Lc) }
                val range = caps.audioCapabilities?.bitrateRange
                AacEncoderInfo(info.name, info.isHardwareAccelerated && !info.isSoftwareOnly, info.isVendor, profiles, (range?.lower ?: 0)..(range?.upper ?: Int.MAX_VALUE))
            }
            .sortedWith(compareByDescending<AacEncoderInfo> { it.hardware }.thenByDescending { it.vendor })
    }

    /**
     * The encoders to try for [settings], in order: the one chosen when the phone has it, else the hardware ones that
     * take the profile, then the phone maker's other ones, Android's software encoder last.
     */
    fun candidates(settings: AacSettings): List<String> {
        val fits = all.filter { settings.profile in it.profiles && settings.bitrateKbps * 1000 in it.bitrates }
        val auto = fits.filter { it.hardware } + fits.filter { !it.hardware && it.vendor } + all.filter { it.name == SOFTWARE }
        val chosen = settings.encoder?.let { name -> all.filter { it.name == name } }.orEmpty()
        return (chosen + auto).map { it.name }.distinct().ifEmpty { listOf(SOFTWARE) }
    }

    /** The encoder the last AAC recording ran on, for Settings. */
    @Volatile var lastUsed: String? = null

    fun profileOf(profile: Int) = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AACObjectLC -> AacProfile.Lc
        MediaCodecInfo.CodecProfileLevel.AACObjectHE -> AacProfile.He
        MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS -> AacProfile.HeV2
        else -> null
    }

    fun codecProfile(profile: AacProfile) = when (profile) {
        AacProfile.Lc -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
        AacProfile.He -> MediaCodecInfo.CodecProfileLevel.AACObjectHE
        AacProfile.HeV2 -> MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS
    }
}

/**
 * PCM (48 kHz stereo 16 bit, the microphone's) to an AAC file in ADTS frames, on the phone's hardware encoder when it
 * has one ([AacEncoders.candidates]; one that won't configure is skipped for the next). Each frame gets its own 7-byte
 * header, so the file plays and seeks without an index, and a recording cut off by a crash is good up to its last
 * whole frame. The PCM is brought to the settings' rate and channels first ([PcmReducer]).
 */
class AacEncoder(file: File, settings: AacSettings) : PcmSink {
    private val aac = settings.valid()
    private val reducer = PcmReducer(aac.sampleRate, aac.channels)
    private val stream = FileOutputStream(file)
    // About a second of audio at 64 kbps: what a killed process can lose (FLAC loses less; it isn't buffered).
    private val out = BufferedOutputStream(stream, 8 * 1024)
    private val codec: MediaCodec = open()
    val encoderName: String = codec.name
    private val info = MediaCodec.BufferInfo()
    private var adts: ByteArray? = null
    private val bytesPerFrame = aac.channels * 2

    /** Frames (samples per channel, at the file's rate) given to the encoder. */
    var frames = 0L
        private set

    init {
        AacEncoders.lastUsed = encoderName
    }

    private fun open(): MediaCodec {
        var failure: Exception? = null
        for (name in AacEncoders.candidates(aac)) {
            val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            try {
                codec.configure(
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, aac.sampleRate, aac.channels).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, AacEncoders.codecProfile(aac.profile))
                        setInteger(MediaFormat.KEY_BIT_RATE, aac.bitrateKbps * 1000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_SIZE)
                    },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                codec.start()
                return codec
            } catch (e: Exception) {
                failure = e
                codec.release()
            }
        }
        throw IllegalStateException("No AAC encoder took $aac", failure)
    }

    override fun write(pcm: ByteArray, size: Int) {
        val reduced = reducer.process(pcm, size)
        val bytes = reduced.pcm
        var offset = 0
        while (offset < reduced.size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) { drain(endOfStream = false); continue }
            val input = codec.getInputBuffer(index)!!
            input.clear()
            val n = minOf(reduced.size - offset, input.remaining() - input.remaining() % bytesPerFrame)
            input.put(bytes, offset, n)
            codec.queueInputBuffer(index, 0, n, frames * 1_000_000 / aac.sampleRate, 0)
            frames += n / bytesPerFrame
            offset += n
            drain(endOfStream = false)
        }
    }

    override fun checkpoint() {
        out.flush()
        stream.fd.sync()
    }

    override fun close() {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, frames * 1_000_000 / aac.sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(endOfStream = false)
        }
        drain(endOfStream = true)
        codec.stop()
        codec.release()
        out.flush()
        stream.fd.sync()
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
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> adts = Adts.headerFor(bytes, aac)
                bytes.isNotEmpty() -> {
                    val header = adts ?: Adts.headerFor(ByteArray(0), aac).also { adts = it }
                    out.write(Adts.withLength(header, bytes.size))
                    out.write(bytes)
                }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val INPUT_SIZE = 16 * 1024
    }
}
