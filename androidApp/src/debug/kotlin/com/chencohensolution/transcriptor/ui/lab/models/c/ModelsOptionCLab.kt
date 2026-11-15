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

private val allProfilesC = setOf(AacProfile.Lc, AacProfile.He, AacProfile.HeV2)

/** A made-up hardware encoder (its name too): the emulator has none, and no one has listed the S25's yet. */
private val madeUpHardwareC = AacEncoderInfo("c2.vendor.aac.hw.encoder", hardware = true, vendor = true, setOf(AacProfile.Lc, AacProfile.He), 16_000..320_000)

@Composable
private fun ModelsLabC(encoders: List<AacEncoderInfo>) {
    var format by remember { mutableStateOf(AudioFormat.AacLive) }
    var level by remember { mutableStateOf(5) }
    var aac by remember { mutableStateOf(AacSettings()) }
    val auto = encoders.firstOrNull { it.hardware && aac.profile in it.profiles }?.name ?: AacEncoders.SOFTWARE
    ModelsScreenC(
        ModelsOptionCState(format, level, aac, encoders, auto),
        ModelsOptionCActions(setFormat = { format = it }, setFlacLevel = { level = it }, setAac = { aac = it }),
    )
}

// Fakes: only the Storage section's audio block is drawn (the rest of Settings is the app's, unchanged). `c` lists
// this device's real AAC encoders (the emulator: Android's software one only); `c-hw` adds a made-up hardware one.
internal val modelsOptionC = LabOption("c", "Ready-made choices, then Custom AAC", "Balanced, Smallest, Stereo and the lossless ways as cards with size an hour; Custom shows every AAC setting") {
    ModelsLabC(AacEncoders.all)
}

internal val modelsOptionCHw = LabOption("c-hw", "c, on a phone with a hardware AAC encoder", "Same as c, with a made-up hardware encoder listed first") {
    ModelsLabC(listOf(madeUpHardwareC) + AacEncoders.all.ifEmpty { listOf(AacEncoderInfo(AacEncoders.SOFTWARE, false, false, allProfilesC, 8_000..510_000)) })
}
