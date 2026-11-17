package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.settings.AacProfile
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.EncodeAfter
import com.chencohensolution.transcriptor.settings.FLAC_LEVELS
import com.chencohensolution.transcriptor.settings.FlacFormat
import com.chencohensolution.transcriptor.settings.PowerPreset
import com.chencohensolution.transcriptor.settings.QualityProfile
import com.chencohensolution.transcriptor.settings.Codec
import com.chencohensolution.transcriptor.settings.wavFirst
import kotlin.math.roundToInt

/** Settings' recording-format pages, opened from its Recording format row. */
enum class FormatPage { Format, Aac, Flac, Mic, Opus, Xhe, AmrWb, AmrNb }

/** The codec a format records to in the end, as it records (WAV first or not). */
private fun codecOf(format: AudioFormat) = Codec.of(format).format(wavFirst = false)

/** The page of one of the other codecs. */
private fun pageOf(codec: Codec) = when (codec) {
    Codec.Opus -> FormatPage.Opus
    Codec.Xhe -> FormatPage.Xhe
    Codec.AmrWb -> FormatPage.AmrWb
    Codec.AmrNb -> FormatPage.AmrNb
    Codec.Aac -> FormatPage.Aac
    Codec.Flac -> FormatPage.Flac
    Codec.Wav -> FormatPage.Format
}

/**
 * Settings › Recording format and the AAC and FLAC pages it opens ([page]): WAV, AAC and FLAC, and under them "Record
 * as WAV, encode afterwards" (when the moment ends, or when charging with 30% battery or more) for AAC and FLAC alike.
 * At the top, the quality presets (High, Normal, Low; Custom once anything is changed by hand) and the battery presets
 * (save battery, right away). AAC's page: profile, sample rate, channels, bit depth (16 only), bitrate and encoder;
 * FLAC's: sample rate, channels, bit depth and the compression effort.
 */
@Composable
fun RecordingFormatPages(page: FormatPage, state: ModelsState, actions: ModelsActions, open: (FormatPage) -> Unit, back: () -> Unit) {
    when (page) {
        FormatPage.Format -> FormatList(state, actions, open, back)
        FormatPage.Mic -> MicrophonePage(state, actions, back)
        // The codecs' settings are on the one page now (owner's request, Nov 17, 2026).
        else -> FormatList(state, actions, open, back)
    }
}

@Composable
private fun aacLine(state: ModelsState): String {
    val aac = state.aac
    val profile = stringResource(when (aac.profile) { AacProfile.Lc -> R.string.rec_format_profile_lc; AacProfile.He -> R.string.rec_format_profile_he; AacProfile.HeV2 -> R.string.rec_format_profile_he2 })
    val channels = stringResource(if (aac.channels == 1) R.string.rec_format_mono else R.string.rec_format_stereo)
    val where = stringResource(if (state.aacEncoder?.hardware == true) R.string.rec_format_summary_hardware else R.string.rec_format_summary_software)
    return stringResource(R.string.rec_format_summary_aac, profile, aac.bitrateKbps, channels, aac.sampleRate / 1000, where)
}

@Composable
private fun encodeLine(state: ModelsState): String = when {
    state.audioFormat.wavFirst ->
        stringResource(if (state.encodeAfter == EncodeAfter.Stop) R.string.rec_format_later_stop_short else R.string.rec_format_later_charging_short)
    else -> stringResource(R.string.rec_format_while_recording_short)
}

@Composable
private fun flacLine(flac: FlacFormat): String =
    stringResource(R.string.rec_format_flac_format, flac.bitDepth, stringResource(if (flac.channels == 1) R.string.rec_format_mono else R.string.rec_format_stereo), flac.sampleRate / 1000)

/** FLAC as it records: 16 bit when the microphone gives no more, whatever the setting. */
private val ModelsState.recordedFlac get() = if (deepCapture) flac else flac.copy(bitDepth = 16)

@Composable
internal fun profileName(profile: QualityProfile) = stringResource(when (profile) {
    QualityProfile.High -> R.string.rec_preset_high
    QualityProfile.Normal -> R.string.rec_preset_normal
    QualityProfile.Low -> R.string.rec_preset_low
    QualityProfile.Music -> R.string.notebooks_record_music
})

@Composable
private fun powerName(preset: PowerPreset) = stringResource(when (preset) {
    PowerPreset.Low -> R.string.rec_preset_power_low
    PowerPreset.Normal -> R.string.rec_preset_power_normal
    PowerPreset.Custom -> R.string.rec_preset_custom
})

/** The shown profile in one line: codec and its settings. */
@Composable
private fun profileLine(state: ModelsState): String = when (codecOf(state.audioFormat)) {
    AudioFormat.AacLive -> stringResource(R.string.rec_format_codec_aac) + " · " + aacLine(state)
    AudioFormat.FlacLive -> stringResource(R.string.rec_format_codec_flac) + " · " + flacLine(state.recordedFlac)
    AudioFormat.Wav -> stringResource(R.string.rec_format_summary_wav)
    else -> otherLine(state, Codec.of(state.audioFormat))
}

/** One of the other codecs in one line: its name, bitrate, channels and rate. */
@Composable
private fun otherLine(state: ModelsState, codec: Codec): String {
    val o = state.profileSettings.other(codec) ?: return codecName(codec)
    return codecName(codec) + " · " + stringResource(R.string.rec_format_kbps_decimal, kbps(o.bitrate)) + " · " +
        stringResource(if (o.channels == 1) R.string.rec_format_mono else R.string.rec_format_stereo) + " · " + stringResource(R.string.rec_format_khz, o.sampleRate / 1000)
}

@Composable
private fun codecName(codec: Codec) = stringResource(when (codec) {
    Codec.Wav -> R.string.rec_format_codec_wav
    Codec.Aac -> R.string.rec_format_codec_aac
    Codec.Flac -> R.string.rec_format_codec_flac
    Codec.Opus -> R.string.rec_format_codec_opus
    Codec.Xhe -> R.string.rec_format_codec_xhe
    Codec.AmrWb -> R.string.rec_format_codec_amrwb
    Codec.AmrNb -> R.string.rec_format_codec_amrnb
})

/** The Recording format row's line in Settings: the presets, then the codec, its settings, and when it encodes. */
@Composable
fun recordingFormatSummary(state: ModelsState): String {
    val choice = state.recordingChoice
    val presets = stringResource(R.string.rec_preset_summary, profileName(state.activeProfile), powerName(choice.power))
    // The settings line is the shown profile's: said only when it is the one moments record with.
    if (state.profile != state.activeProfile) return presets
    val withMic = state.micInput?.let { presets + " · " + micName(it.name, it.kind) } ?: presets
    return withMic + "\n" + when (codecOf(state.audioFormat)) {
        AudioFormat.AacLive -> stringResource(R.string.rec_format_codec_aac) + " · " + aacLine(state) + " · " + encodeLine(state)
        AudioFormat.FlacLive -> stringResource(R.string.rec_format_summary_flac, state.flacLevel, FLAC_LEVELS.last) + " · " + flacLine(state.recordedFlac) + " · " + encodeLine(state)
        AudioFormat.Wav -> stringResource(R.string.rec_format_summary_wav)
        else -> otherLine(state, Codec.of(state.audioFormat)) + " · " + encodeLine(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatTopBar(title: String, back: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
        title = { Text(title) },
    )
}

/**
 * Settings › Recording settings, one page from top to bottom, each part narrowing the next (owner's request, Nov 17,
 * 2026): the microphone (what it gives), the profile (Low, Normal, High, Music), the codec, the sound the codec can
 * take (rate, channels, bit depth: lossless only; the AMRs' fixed), that codec's own settings, then when to compress
 * and transcribe. No page per codec.
 */
@Composable
private fun FormatList(state: ModelsState, actions: ModelsActions, open: (FormatPage) -> Unit, back: () -> Unit) {
    val codec = Codec.of(state.audioFormat)
    Scaffold(topBar = { FormatTopBar(stringResource(R.string.rec_format_format), back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            MicrophoneRow(state) { open(FormatPage.Mic) }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Presets(state, actions)
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            // 1. The codec: the phone's (only those it has), each with what it is for.
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.rec_format_codec), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                (listOf(Codec.Wav, Codec.Aac, Codec.Flac) + listOf(Codec.Opus, Codec.Xhe, Codec.AmrWb, Codec.AmrNb).filter { it in state.codecs }).forEach { c ->
                    CodecRow(codec == c, codecName(c), stringResource(codecShort(c)), page = false) {
                        if (codec != c) actions.setAudioFormat(c.format(state.audioFormat.wavFirst))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            // 2. The sound this codec takes.
            SoundPart(state, actions, codec)
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            // 3. This codec's own settings.
            when (codec) {
                Codec.Aac -> AacSettingsPart(state, actions)
                Codec.Flac -> {
                    FlacFormatPart(state.flac, state.deepCapture)
                    FlacEffortPart(state.flacLevel, state.audioFormat, actions.setFlacLevel)
                }
                Codec.Wav -> FormatPageNote(stringResource(R.string.rec_format_wav_short, state.recordingChoice.copy(audioFormat = AudioFormat.Wav, flac = state.recordedFlac).megabytesPerHourWhileRecording))
                else -> OtherCodecPart(state, actions, codec)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            // 4. When to compress and transcribe.
            TimingPart(state, actions)
        }
    }
}

/**
 * The recording profiles (owner's request, Nov 17, 2026): High, Normal and Low (the one picked records moments) and
 * Music (the Music mode's), each with its own settings, edited on this page while its tab is shown; Reset puts it
 * back to its defaults. Then the battery presets (when encoding and "After" transcription run, for all).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Presets(state: ModelsState, actions: ModelsActions) {
    val choice = state.recordingChoice
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        FormatLabel(stringResource(R.string.rec_preset_quality))
        val profiles = QualityProfile.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            profiles.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = state.profile == p, onClick = { actions.showProfile(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, profiles.size),
                ) { Text(profileName(p), maxLines = 1) }
            }
        }
        FormatNote(stringResource(if (state.profile == QualityProfile.Music) R.string.rec_profile_music_note else R.string.rec_profile_note))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(profileLine(state), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = actions.resetProfile) { Text(stringResource(R.string.rec_profile_reset)) }
        }
        // How long a moment can be with the space left (while recording: the WAV, when it records as WAV first).
        // The other codecs as they record: their bitrate; WAV first: the WAV.
        val other = state.profileSettings.other(Codec.of(state.audioFormat))?.takeIf { !state.audioFormat.wavFirst }
        val perHour = other?.megabytesPerHour ?: choice.copy(flac = state.recordedFlac).megabytesPerHourWhileRecording
        if (state.freeBytes > 0 && perHour > 0) {
            val hours = (state.freeBytes / 1_000_000 / perHour).toInt()
            FormatNote(stringResource(R.string.rec_preset_hours_left, hours, state.freeBytes / 1_000_000_000))
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** When to compress and transcribe: on the charger (save battery) or right away; Music's own on its tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimingPart(state: ModelsState, actions: ModelsActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        // When to compress and transcribe: two modes, no mix (owner's call, Nov 17, 2026). Music has its own (it isn't
        // transcribed: only when it is compressed).
        FormatLabel(stringResource(R.string.rec_preset_power))
        val music = state.profile == QualityProfile.Music
        val power = listOf(PowerPreset.Low, PowerPreset.Normal)
        val picked = if (state.powerSaving) PowerPreset.Low else PowerPreset.Normal
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            power.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = picked == p, onClick = { actions.setPower(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, power.size),
                ) { Text(powerName(p)) }
            }
        }
        FormatNote(stringResource(when {
            music && state.powerSaving -> R.string.rec_preset_power_low_music
            music -> R.string.rec_preset_power_normal_music
            state.powerSaving -> R.string.rec_preset_power_low_note
            else -> R.string.rec_preset_power_normal_note
        }))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CodecRow(selected: Boolean, title: String, note: String, page: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (page) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

/**
 * "Record as WAV, encode afterwards", for AAC and FLAC alike, and when. Off for WAV (nothing to encode); turning it on
 * keeps the codec: AAC live becomes WAV then AAC, FLAC live WAV then FLAC.
 */
@Composable
private fun EncodeLater(state: ModelsState, actions: ModelsActions) {
    val codec = codecOf(state.audioFormat)
    val on = state.audioFormat.wavFirst
    val enabled = codec != AudioFormat.Wav
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, enabled = enabled, role = Role.Checkbox) { checked ->
            actions.setAudioFormat(Codec.of(state.audioFormat).format(checked))
        }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = null, enabled = enabled)
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(R.string.rec_format_later), style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            Text(
                stringResource(if (enabled) R.string.rec_format_later_note else R.string.rec_format_later_wav),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (on) {
        Column(Modifier.selectableGroup().padding(start = 52.dp, end = 20.dp, bottom = 16.dp)) {
            listOf(
                EncodeAfter.Stop to (R.string.rec_format_later_stop to R.string.rec_format_later_stop_note),
                EncodeAfter.Charging to (R.string.rec_format_later_charging to R.string.rec_format_later_charging_note),
            ).forEach { (option, labels) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = state.encodeAfter == option, role = Role.RadioButton) { actions.setEncodeAfter(option) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.encodeAfter == option, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(labels.first), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(labels.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


/** A bitrate in kbps as people write it: 32, 23.85, 12.2. */
private fun kbps(bps: Int) = if (bps % 1000 == 0) "${bps / 1000}" else "%.2f".format(java.util.Locale.ROOT, bps / 1000.0).trimEnd('0')

private fun codecShort(codec: Codec) = when (codec) {
    Codec.Wav -> R.string.rec_format_wav_line
    Codec.Aac -> R.string.rec_format_aac_short
    Codec.Flac -> R.string.rec_format_flac_lossless
    else -> otherShort(codec)
}

private fun otherShort(codec: Codec) = when (codec) {
    Codec.Opus -> R.string.rec_format_opus_short
    Codec.Xhe -> R.string.rec_format_xhe_short
    Codec.AmrWb -> R.string.rec_format_amrwb_short
    else -> R.string.rec_format_amrnb_short
}

/**
 * Opus', xHE-AAC's, AMR-WB's or AMR-NB's page: the bitrate as chips with the size an hour, and what the codec is good
 * for. The sound (rate, channels) is the profile's, on Recording settings; the AMRs have their own (16 or 8 kHz mono).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OtherCodecPart(state: ModelsState, actions: ModelsActions, codec: Codec) {
    val spec = state.profileSettings.other(codec)
    FormatPageNote(stringResource(when (codec) {
        Codec.Opus -> R.string.rec_format_opus_note
        Codec.Xhe -> R.string.rec_format_xhe_note
        Codec.AmrWb -> R.string.rec_format_amrwb_note
        else -> R.string.rec_format_amrnb_note
    }))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rec_format_bitrate), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (spec != null) Text(stringResource(R.string.rec_format_size, spec.megabytesPerHour), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            codec.bitrates.forEach { bps ->
                FilterChip(
                    selected = spec?.bitrate == bps, onClick = { actions.setBitrate(codec, bps) },
                    label = { Text(stringResource(R.string.rec_format_kbps_decimal, kbps(bps))) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FormatPageNote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AacSettingsPart(state: ModelsState, actions: ModelsActions) {
    val aac = state.aac
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        FormatLabel(stringResource(R.string.rec_format_profile))
        // Only the profiles one of the phone's encoders takes.
        val offered = state.aacEncoders.flatMap { it.profiles }.toSet().ifEmpty { setOf(AacProfile.Lc) }
        val profiles = listOf(AacProfile.Lc to R.string.rec_format_profile_lc, AacProfile.He to R.string.rec_format_profile_he, AacProfile.HeV2 to R.string.rec_format_profile_he2)
            .filter { it.first in offered }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            profiles.forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = aac.profile == p, onClick = { actions.setAac(aac.copy(profile = p).valid()) },
                    // HE needs 24 or 48 kHz, HE v2 stereo: the sound is set on Recording settings.
                    enabled = aac.sampleRate in AacSettings.rates(p) && (p != AacProfile.HeV2 || aac.channels == 2),
                    shape = SegmentedButtonDefaults.itemShape(i, profiles.size),
                ) { Text(stringResource(label)) }
            }
        }
        FormatNote(stringResource(when (aac.profile) {
            AacProfile.Lc -> R.string.rec_format_profile_lc_note
            AacProfile.He -> R.string.rec_format_profile_he_note
            AacProfile.HeV2 -> R.string.rec_format_profile_he2_note
        }))


        Row(Modifier.padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rec_format_bitrate), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.rec_format_size, aac.megabytesPerHour), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Only the bitrates an encoder that takes this profile takes.
            aac.bitrates.filter { kbps -> state.aacEncoders.isEmpty() || state.aacEncoders.any { aac.profile in it.profiles && kbps * 1000 in it.bitrates } }.forEach { kbps ->
                FilterChip(
                    selected = aac.bitrateKbps == kbps, onClick = { actions.setAac(aac.copy(bitrateKbps = kbps)) },
                    label = { Text(stringResource(R.string.rec_format_kbps, kbps)) },
                )
            }
        }
        if (aac.profile != AacProfile.Lc && aac.bitrateKbps > 64) FormatNote(stringResource(R.string.rec_format_bitrate_he_high))

        FormatLabel(stringResource(R.string.rec_format_encoder))
        AacEncoderPicker(state, actions)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AacEncoderPicker(state: ModelsState, actions: ModelsActions) {
    var open by remember { mutableStateOf(false) }
    val running = state.aacEncoder
    val auto = state.aacEncoders.firstOrNull { it.name == state.autoAacEncoder }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.aac.encoder == null) stringResource(R.string.rec_format_encoder_auto) else isolated(state.aac.encoder.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (running != null) Text(
                    stringResource(if (running.hardware) R.string.rec_format_encoder_hardware else R.string.rec_format_encoder_software, isolated(running.name)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.rec_format_encoder_auto))
                        if (auto != null) Text(isolated(auto.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = { open = false; actions.setAac(state.aac.copy(encoder = null)) },
            )
            state.aacEncoders.forEach { e ->
                val fits = state.aac.profile in e.profiles
                DropdownMenuItem(
                    enabled = fits,
                    text = {
                        Column {
                            Text(isolated(e.name))
                            Text(
                                stringResource(when {
                                    !fits -> R.string.rec_format_encoder_no_profile
                                    e.hardware -> R.string.rec_format_encoder_kind_hardware
                                    else -> R.string.rec_format_encoder_kind_software
                                }),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { open = false; actions.setAac(state.aac.copy(encoder = e.name)) },
                )
            }
        }
    }
    if (state.aacEncoders.none { it.hardware }) FormatNote(stringResource(R.string.rec_format_encoder_none_hardware))
}

/** FLAC's size an hour at the shared sound (sample rate, channels and bit depth are on Recording settings). */
@Composable
private fun FlacFormatPart(stored: FlacFormat, deep: Boolean) {
    val flac = if (deep) stored else stored.copy(bitDepth = 16)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(flacLine(flac), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        FormatNote(stringResource(R.string.rec_format_size, flac.megabytesPerHour))
        HorizontalDivider(Modifier.padding(top = 16.dp))
    }
}

/**
 * The sound both codecs record at, on Recording settings (owner's request, Nov 17, 2026): sample rate and channels for
 * AAC and FLAC alike, and the bit depth for FLAC and WAV when the microphone gives more than 16 bits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundPart(state: ModelsState, actions: ModelsActions, codec: Codec) {
    val choice = state.recordingChoice
    val rate = choice.sampleRate
    val channels = choice.channels
    val bits = state.recordedFlac.bitDepth
    val fixed = codec.fixedRate
    if (fixed != null) {
        // The AMRs have one sound of their own: said, nothing to pick.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            FormatLabel(stringResource(R.string.rec_format_rate))
            FormatNote(stringResource(R.string.rec_format_amr_sound, fixed / 1000))
            Spacer(Modifier.height(8.dp))
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        FormatLabel(stringResource(R.string.rec_format_rate))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FlacFormat.RATES.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = rate == r, onClick = { actions.setSound(r, channels, bits) },
                    shape = SegmentedButtonDefaults.itemShape(i, FlacFormat.RATES.size),
                ) { Text(stringResource(R.string.rec_format_khz, r / 1000)) }
            }
        }
        FormatNote(stringResource(R.string.rec_format_flac_rate_note))

        FormatLabel(stringResource(R.string.rec_format_channels))
        val options = listOf(1 to R.string.rec_format_mono, 2 to R.string.rec_format_stereo)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (c, label) ->
                SegmentedButton(
                    selected = channels == c, onClick = { actions.setSound(rate, c, bits) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        FormatNote(stringResource(R.string.rec_format_flac_channels_note))

        // Bit depth is the lossless codecs' (WAV, FLAC): a lossy one keeps none of its own.
        if (codec == Codec.Wav || codec == Codec.Flac) if (state.deepCapture) {
            FormatLabel(stringResource(R.string.rec_format_depth))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                FlacFormat.DEPTHS.forEachIndexed { i, b ->
                    SegmentedButton(
                        selected = bits == b, onClick = { actions.setSound(rate, channels, b) },
                        shape = SegmentedButtonDefaults.itemShape(i, FlacFormat.DEPTHS.size),
                    ) { Text(stringResource(R.string.rec_format_bits, b)) }
                }
            }
            FormatNote(stringResource(R.string.rec_format_depth_note))
        } else {
            // Not a choice with this microphone, but said: it records 16 bit.
            FormatLabel(stringResource(R.string.rec_format_depth))
            Text(stringResource(R.string.rec_format_bits, 16), style = MaterialTheme.typography.bodyMedium)
            FormatNote(stringResource(R.string.rec_format_depth_fixed))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** FLAC's compression level, as the app's Settings has it. */
@Composable
private fun FlacEffortPart(level: Int, format: AudioFormat, set: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.flac_effort), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.flac_effort_value, level, FLAC_LEVELS.last), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = level.toFloat(),
            onValueChange = { v -> v.roundToInt().let { if (it != level) set(it) } },
            valueRange = FLAC_LEVELS.first.toFloat()..FLAC_LEVELS.last.toFloat(),
            steps = FLAC_LEVELS.last - FLAC_LEVELS.first - 1,
        )
        Row {
            Text(stringResource(R.string.flac_effort_fast), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.flac_effort_small), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FormatNote(stringResource(if (format == AudioFormat.FlacLive) R.string.flac_effort_note_live else R.string.flac_effort_note_after))
    }
}

@Composable
private fun FormatLabel(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
private fun FormatNote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
