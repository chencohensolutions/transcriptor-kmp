package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.recording.AacEncoderInfo
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.EncodeAfter

/** Settings' audio block: how moments are recorded, with FLAC's and AAC's settings and the phone's AAC encoders. */
data class ModelsOptionB2State(
    val format: AudioFormat,
    val flacLevel: Int,
    val aac: AacSettings,
    /** When "record as WAV, encode afterwards" encodes. */
    val encodeAfter: EncodeAfter,
    /** The phone's AAC encoders, hardware first ([com.chencohensolution.transcriptor.recording.AacEncoders.all]). */
    val encoders: List<AacEncoderInfo>,
    /** What "automatic" would run for [aac]: the first of [com.chencohensolution.transcriptor.recording.AacEncoders.candidates]. */
    val autoEncoder: String,
) {
    /** The encoder the next AAC moment runs on. */
    val encoder get() = encoders.firstOrNull { it.name == (aac.encoder ?: autoEncoder) } ?: encoders.firstOrNull { it.name == autoEncoder }
}

class ModelsOptionB2Actions(
    val back: () -> Unit = {},
    val setFormat: (AudioFormat) -> Unit = {},
    val setFlacLevel: (Int) -> Unit = {},
    val setAac: (AacSettings) -> Unit = {},
    val setEncodeAfter: (EncodeAfter) -> Unit = {},
)
