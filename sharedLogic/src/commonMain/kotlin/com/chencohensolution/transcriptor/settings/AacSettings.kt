package com.chencohensolution.transcriptor.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** AAC's profiles the recorder offers. HE adds SBR (high frequencies rebuilt from a little data); v2 adds parametric stereo. */
@Serializable
enum class AacProfile {
    /** AAC-LC: the plainest, cheapest to encode and decode, and plays everywhere. */
    @SerialName("lc") Lc,

    /** HE-AAC (v1): about half LC's size for the same sound at low bitrates; needs 24 or 48 kHz. */
    @SerialName("he") He,

    /** HE-AAC v2: HE with parametric stereo, the smallest stereo; stereo only. */
    @SerialName("he-v2") HeV2,
}

/**
 * How "AAC live" records: [sampleRate] and [channels] of the file (the microphone is read at 48 kHz stereo either way,
 * for live transcription, and brought down to these before the encoder), the [profile], the bitrate in kbps, and the
 * MediaCodec [encoder] by name (null: the phone's hardware AAC encoder when it has one, else Android's software one).
 * [valid] puts any combination back to one the encoders take.
 */
@Serializable
data class AacSettings(
    val profile: AacProfile = AacProfile.Lc,
    val sampleRate: Int = 48_000,
    // The owner's settings (Nov 15, 2026): stereo at 256 kbps, about 115 MB an hour.
    val channels: Int = 2,
    val bitrateKbps: Int = 256,
    val encoder: String? = null,
) {
    /** The sample rates for [profile]: whole fractions of the microphone's 48 kHz (no resampling filter beyond a low-pass). */
    val sampleRates get() = rates(profile)

    /** The channel counts for [profile]: HE-AAC v2 is stereo only. */
    val channelCounts get() = if (profile == AacProfile.HeV2) listOf(2) else listOf(1, 2)

    /** The bitrates (kbps) offered: all of [BITRATES]. */
    val bitrates get() = bitrates(profile, channels)

    /** This, with each choice moved to the nearest one its profile allows. */
    fun valid(): AacSettings {
        val rate = sampleRates.minBy { kotlin.math.abs(it - sampleRate) }
        val ch = if (channels in channelCounts) channels else channelCounts.first()
        val rates = bitrates(profile, ch)
        return copy(sampleRate = rate, channels = ch, bitrateKbps = rates.minBy { kotlin.math.abs(it - bitrateKbps) })
    }

    /** About how many MB an hour of audio takes. */
    val megabytesPerHour get() = bitrateKbps * 3600 / 8 / 1000

    companion object {
        fun rates(profile: AacProfile) = if (profile == AacProfile.Lc) listOf(16_000, 24_000, 48_000) else listOf(24_000, 48_000)

        /**
         * Every bitrate offered, whatever the profile: the encoder takes each (checked on Android's), though HE
         * gains nothing over LC above about 64 kbps.
         */
        val BITRATES = listOf(16, 24, 32, 48, 64, 96, 128, 160, 192, 256, 320)

        @Suppress("UNUSED_PARAMETER")
        fun bitrates(profile: AacProfile, channels: Int): List<Int> = BITRATES
    }
}
