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

private val allProfiles = setOf(AacProfile.Lc, AacProfile.He, AacProfile.HeV2)

/** A made-up hardware encoder (its name too): the emulator has none, and no one has listed the S25's yet. */
private val madeUpHardware = AacEncoderInfo("c2.vendor.aac.hw.encoder", hardware = true, vendor = true, setOf(AacProfile.Lc, AacProfile.He), 16_000..320_000)

@Composable
private fun ModelsLabA(encoders: List<AacEncoderInfo>) {
    var format by remember { mutableStateOf(AudioFormat.AacLive) }
    var level by remember { mutableStateOf(5) }
    var aac by remember { mutableStateOf(AacSettings()) }
    val auto = encoders.firstOrNull { it.hardware && aac.profile in it.profiles }?.name ?: AacEncoders.SOFTWARE
    ModelsScreenA(
        ModelsOptionAState(format, level, aac, encoders, auto),
        ModelsOptionAActions(setFormat = { format = it }, setFlacLevel = { level = it }, setAac = { aac = it }),
    )
}

// Fakes: only the Storage section's audio block is drawn (the rest of Settings is the app's, unchanged). `a` lists
// this device's real AAC encoders (the emulator: Android's software one only); `a-hw` adds a made-up hardware one.
internal val modelsOptionA = LabOption("a", "One list, AAC first, its settings under it", "AAC as a fourth way; profile, rate, channels, bitrate, encoder right below") {
    ModelsLabA(AacEncoders.all)
}

internal val modelsOptionAHw = LabOption("a-hw", "a, on a phone with a hardware AAC encoder", "Same as a, with a made-up hardware encoder listed first") {
    ModelsLabA(listOf(madeUpHardware) + AacEncoders.all.ifEmpty { listOf(AacEncoderInfo(AacEncoders.SOFTWARE, false, false, allProfiles, 8_000..510_000)) })
}
