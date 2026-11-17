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

/**
 * Settings › Recording format's quality presets: each sets the codec and its settings; anything changed by hand reads
 * as [Custom]. Whether it records as WAV first is the power preset's ([PowerPreset]), and is kept.
 */
enum class QualityPreset {
    /** FLAC, 48 kHz stereo, 24 bit: lossless, for music (a concert). About 700 MB an hour. */
    High,

    /** AAC-LC, 48 kHz stereo, 256 kbps: about 115 MB an hour (the owner's settings, Nov 15, 2026). */
    Normal,

    /** AAC-LC, 24 kHz mono, 48 kbps: speech only, about 22 MB an hour. */
    Low,

    /** Anything else (WAV included). */
    Custom;

    companion object {
        val HIGH_FLAC = FlacFormat(48_000, 2, 24)
        val NORMAL_AAC = AacSettings(AacProfile.Lc, 48_000, 2, 256)
        val LOW_AAC = AacSettings(AacProfile.Lc, 24_000, 1, 48)
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
    val quality: QualityPreset
        get() {
            val a = aac.valid()
            fun same(p: AacSettings) = a.profile == p.profile && a.sampleRate == p.sampleRate && a.channels == p.channels && a.bitrateKbps == p.bitrateKbps
            return when {
                audioFormat.isFlac && flac.valid() == QualityPreset.HIGH_FLAC -> QualityPreset.High
                audioFormat.isAac && same(QualityPreset.NORMAL_AAC) -> QualityPreset.Normal
                audioFormat.isAac && same(QualityPreset.LOW_AAC) -> QualityPreset.Low
                else -> QualityPreset.Custom
            }
        }

    val power: PowerPreset
        get() = when {
            encodeAfter == EncodeAfter.Charging && transcribeWhen == EncodeAfter.Charging && (audioFormat.wavFirst || audioFormat == AudioFormat.Wav) -> PowerPreset.Low
            encodeAfter == EncodeAfter.Stop && transcribeWhen == EncodeAfter.Stop -> PowerPreset.Normal
            else -> PowerPreset.Custom
        }

    /** These settings with [preset]'s codec and its settings; WAV first stays as it was, the AAC encoder too. */
    fun with(preset: QualityPreset): RecordingChoice {
        val first = audioFormat.wavFirst
        fun aacOf(p: AacSettings) = p.copy(encoder = aac.encoder)
        return when (preset) {
            QualityPreset.High -> copy(audioFormat = if (first) AudioFormat.WavThenFlac else AudioFormat.FlacLive, flac = QualityPreset.HIGH_FLAC)
            QualityPreset.Normal -> copy(audioFormat = if (first) AudioFormat.WavThenAac else AudioFormat.AacLive, aac = aacOf(QualityPreset.NORMAL_AAC))
            QualityPreset.Low -> copy(audioFormat = if (first) AudioFormat.WavThenAac else AudioFormat.AacLive, aac = aacOf(QualityPreset.LOW_AAC))
            QualityPreset.Custom -> this
        }
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
