package com.chencohensolution.transcriptor.settings

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * How a FLAC (and the WAV kept before it) is laid out: [sampleRate] (a whole fraction of the microphone's 48 kHz),
 * [channels] (1 averages the two) and [bitDepth] (16, or 24: the microphone is then read in floating point and the
 * WAV and FLAC keep 24 bits). The WAV kept before "WAV, then FLAC" is always 48 kHz stereo at [bitDepth]; the rate and
 * channels apply when it is encoded.
 */
@Serializable
data class FlacFormat(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    val bitDepth: Int = 16,
) {
    /** This, with each choice moved to the nearest one offered. */
    fun valid() = copy(
        sampleRate = RATES.minBy { abs(it - sampleRate) },
        channels = channels.coerceIn(1, 2),
        bitDepth = if (bitDepth >= 24) 24 else 16,
    )

    /** About how many MB an hour takes: FLAC keeps about 60% of the PCM at 16 bits, 70% at 24 (the low bits are noise). */
    val megabytesPerHour get() = (sampleRate.toLong() * channels * bitDepth / 8 * 3600 / 1_000_000 * (if (bitDepth == 24) 0.7 else 0.6)).toInt()

    companion object {
        val RATES = listOf(16_000, 24_000, 48_000)
        val DEPTHS = listOf(16, 24)
    }
}

/** The codec a profile ends up in (whether it records as WAV first is the battery's, [SettingsStore.wavFirst]). */
@Serializable
enum class Codec {
    @kotlinx.serialization.SerialName("wav") Wav,
    @kotlinx.serialization.SerialName("aac") Aac,
    @kotlinx.serialization.SerialName("flac") Flac;

    /** The [AudioFormat] this codec records as: as it records, or as WAV first then this. */
    fun format(wavFirst: Boolean) = when (this) {
        Wav -> AudioFormat.Wav
        Aac -> if (wavFirst) AudioFormat.WavThenAac else AudioFormat.AacLive
        Flac -> if (wavFirst) AudioFormat.WavThenFlac else AudioFormat.FlacLive
    }

    companion object {
        fun of(format: AudioFormat) = when {
            format.isAac -> Aac
            format.isFlac -> Flac
            else -> Wav
        }
    }
}

/**
 * One recording profile's settings (owner's request, Nov 17, 2026: Low, Normal, High and Music each configurable,
 * no "custom"): the [codec], AAC's and FLAC's settings (the sound, rate and channels, kept the same in both), FLAC's
 * compression level, and the microphone (null: the phone's own).
 */
@Serializable
data class ProfileSettings(
    val codec: Codec,
    val aac: AacSettings,
    val flac: FlacFormat,
    val flacLevel: Int = FLAC_DEFAULT_LEVEL,
    val micInput: com.chencohensolution.transcriptor.recording.MicInput? = null,
    /**
     * Music's own "Record as WAV, encode afterwards" (owner's request, Nov 17, 2026: optional for Music, off by
     * default); the other profiles share [SettingsStore.wavFirst].
     */
    val wavFirst: Boolean = false,
) {
    fun valid() = copy(aac = aac.valid(), flac = flac.valid(), flacLevel = flacLevel.coerceIn(FLAC_LEVELS))
}

/**
 * The recording profiles: [High], [Normal] or [Low] records moments (Live, After, No transcript), the one picked in
 * Settings › Recording settings; [Music] records the Record bar's Music mode (a concert). Each starts from [defaults]
 * and keeps what the user changes in it.
 */
enum class QualityProfile(val key: String) {
    /** FLAC, 48 kHz stereo, 16 bit: lossless. 16 bit: the owner's S25 gives its own microphones as 16-bit PCM only. */
    High("high"),

    /** AAC-LC, 48 kHz stereo, 256 kbps: about 115 MB an hour (the owner's settings, Nov 15, 2026). */
    Normal("normal"),

    /** AAC-LC, 24 kHz mono, 48 kbps: speech only, about 22 MB an hour. */
    Low("low"),

    /** The Music mode: FLAC 48 kHz stereo, encoded as it records unless its own WAV first is on; the microphone opened without voice processing. */
    Music("music");

    /** What this profile starts as, and goes back to on Reset. */
    val defaults: ProfileSettings
        get() = when (this) {
            High, Music -> ProfileSettings(Codec.Flac, NORMAL_AAC, HIGH_FLAC)
            Normal -> ProfileSettings(Codec.Aac, NORMAL_AAC, FlacFormat(48_000, 2, 16))
            Low -> ProfileSettings(Codec.Aac, LOW_AAC, FlacFormat(24_000, 1, 16))
        }

    companion object {
        val HIGH_FLAC = FlacFormat(48_000, 2, 16)
        val NORMAL_AAC = AacSettings(AacProfile.Lc, 48_000, 2, 256)
        val LOW_AAC = AacSettings(AacProfile.Lc, 24_000, 1, 48)

        /** The profile kept as [key]; null for anything else. */
        fun of(key: String?) = entries.firstOrNull { it.key == key }

        /** The profiles moments record with (not Music). */
        val forMoments = listOf(High, Normal, Low)
    }
}

/**
 * Settings › Recording format's power presets: when the audio is encoded and "After" moments transcribed. Live
 * transcription (the Record bar's Live / After / No transcript) isn't part of it.
 */
enum class PowerPreset {
    /** WAV while recording (no encoder running), encoded and transcribed once the phone charges with 30% or more. */
    Low,

    /** Encoded and transcribed right away: while recording or as soon as the moment stops. */
    Normal,

    /** Anything else. */
    Custom,
}

/** True when [format] records as WAV and encodes afterwards. */
val AudioFormat.wavFirst get() = this == AudioFormat.WavThenAac || this == AudioFormat.WavThenFlac

/** True when [format] ends up as FLAC. */
val AudioFormat.isFlac get() = this == AudioFormat.FlacLive || this == AudioFormat.WavThenFlac

/** True when [format] ends up as AAC. */
val AudioFormat.isAac get() = this == AudioFormat.AacLive || this == AudioFormat.WavThenAac

/** The recording settings the presets read and set, as one value (for the presets' logic and its tests). */
data class RecordingChoice(
    val audioFormat: AudioFormat,
    val aac: AacSettings,
    val flac: FlacFormat,
    val encodeAfter: EncodeAfter,
    val transcribeWhen: EncodeAfter,
) {
    /**
     * About how many MB an hour takes on the phone while recording: the WAV when it records as WAV first (16 bit before
     * AAC, FLAC's depth before FLAC), else the codec's size. What limits how long a moment can be.
     */
    val megabytesPerHourWhileRecording: Int
        get() {
            val wavBits = if (audioFormat == AudioFormat.WavThenAac) 16 else flac.valid().bitDepth
            // The WAV is at the shared sound too (rate and channels).
            return when {
                audioFormat == AudioFormat.Wav || audioFormat.wavFirst -> (sampleRate.toLong() * channels * wavBits / 8 * 3600 / 1_000_000).toInt()
                audioFormat.isAac -> aac.valid().megabytesPerHour
                else -> flac.valid().megabytesPerHour
            }
        }

    val power: PowerPreset
        get() = when {
            encodeAfter == EncodeAfter.Charging && transcribeWhen == EncodeAfter.Charging && (audioFormat.wavFirst || audioFormat == AudioFormat.Wav) -> PowerPreset.Low
            encodeAfter == EncodeAfter.Stop && transcribeWhen == EncodeAfter.Stop -> PowerPreset.Normal
            else -> PowerPreset.Custom
        }

    /**
     * The sample rate and channels both codecs record at (owner's request, Nov 17, 2026: one setting, not one per
     * codec): the codec in use's, AAC's when it's AAC, else FLAC's.
     */
    val sampleRate get() = if (audioFormat.isAac) aac.valid().sampleRate else flac.valid().sampleRate
    val channels get() = if (audioFormat.isAac) aac.valid().channels else flac.valid().channels

    /**
     * These settings at [rate] and [channels] for AAC and FLAC alike, and FLAC (and the WAV before it) at [bits]. An
     * AAC profile that can't take them gives way: HE v2 to HE for mono, HE to LC at 16 kHz.
     */
    fun withSound(rate: Int, channels: Int, bits: Int = flac.bitDepth): RecordingChoice {
        var profile = aac.profile
        if (profile == AacProfile.HeV2 && channels == 1) profile = AacProfile.He
        if (rate !in AacSettings.rates(profile)) profile = AacProfile.Lc
        return copy(
            aac = aac.copy(profile = profile, sampleRate = rate, channels = channels).valid(),
            flac = flac.copy(sampleRate = rate, channels = channels, bitDepth = bits).valid(),
        )
    }

    /** These settings with [preset]'s timing: Low records as WAV first (the codec kept), Normal leaves that as it is. */
    fun with(preset: PowerPreset): RecordingChoice = when (preset) {
        PowerPreset.Low -> copy(
            audioFormat = when (audioFormat) {
                AudioFormat.AacLive -> AudioFormat.WavThenAac
                AudioFormat.FlacLive -> AudioFormat.WavThenFlac
                else -> audioFormat
            },
            encodeAfter = EncodeAfter.Charging, transcribeWhen = EncodeAfter.Charging,
        )
        PowerPreset.Normal -> copy(encodeAfter = EncodeAfter.Stop, transcribeWhen = EncodeAfter.Stop)
        PowerPreset.Custom -> this
    }
}
