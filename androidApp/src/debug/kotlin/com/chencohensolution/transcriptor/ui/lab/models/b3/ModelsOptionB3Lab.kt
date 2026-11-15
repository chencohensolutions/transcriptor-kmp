package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.recording.AacEncoderInfo
import com.chencohensolution.transcriptor.recording.AacEncoders
import com.chencohensolution.transcriptor.settings.AacProfile
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.EncodeAfter

private val allProfilesB3 = setOf(AacProfile.Lc, AacProfile.He, AacProfile.HeV2)

/** A made-up hardware encoder (its name too): the emulator has none, and no one has listed the S25's yet. */
private val madeUpHardwareB3 = AacEncoderInfo("c2.vendor.aac.hw.encoder", hardware = true, vendor = true, setOf(AacProfile.Lc, AacProfile.He), 16_000..320_000)

@Composable
private fun ModelsLabB3(encoders: List<AacEncoderInfo>) {
    var format by remember { mutableStateOf(AudioFormat.AacLive) }
    var level by remember { mutableStateOf(5) }
    var aac by remember { mutableStateOf(AacSettings()) }
    var after by remember { mutableStateOf(EncodeAfter.Stop) }
    val auto = encoders.firstOrNull { it.hardware && aac.profile in it.profiles }?.name ?: AacEncoders.SOFTWARE
    ModelsScreenB3(
        ModelsOptionB3State(format, level, aac, after, encoders, auto),
        ModelsOptionB3Actions(setFormat = { format = it }, setFlacLevel = { level = it }, setAac = { aac = it }, setEncodeAfter = { after = it }),
    )
}

// Fakes: only the Storage section's audio block is drawn (the rest of Settings is the app's, unchanged). `b3` lists
// this device's real AAC encoders (the emulator: Android's software one only); `b3-hw` adds a made-up hardware one.
internal val modelsOptionB3 = LabOption("b3", "b2, with record as WAV on Recording format", "WAV, AAC, FLAC and under them one record-as-WAV-encode-afterwards for both; AAC and FLAC pages hold only their settings") {
    ModelsLabB3(AacEncoders.all)
}

internal val modelsOptionB3Hw = LabOption("b3-hw", "b3, on a phone with a hardware AAC encoder", "Same as b3, with a made-up hardware encoder listed first") {
    ModelsLabB3(listOf(madeUpHardwareB3) + AacEncoders.all.ifEmpty { listOf(AacEncoderInfo(AacEncoders.SOFTWARE, false, false, allProfilesB3, 8_000..510_000)) })
}
