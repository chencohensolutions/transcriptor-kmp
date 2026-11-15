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
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.EncodeAfter
import com.chencohensolution.transcriptor.settings.FLAC_LEVELS
import kotlin.math.roundToInt

/** Settings' recording-format pages, opened from its Recording format row. */
enum class FormatPage { Format, Aac, Flac }

/** The codec a format records to in the end. */
private fun codecOf(format: AudioFormat) = when (format) {
    AudioFormat.Wav -> AudioFormat.Wav
    AudioFormat.AacLive, AudioFormat.WavThenAac -> AudioFormat.AacLive
    AudioFormat.FlacLive, AudioFormat.WavThenFlac -> AudioFormat.FlacLive
}

/**
 * Settings › Recording format and the AAC and FLAC pages it opens ([page]): WAV, AAC and FLAC, and under them "Record
 * as WAV, encode afterwards" (when the moment ends, or when charging with 30% battery or more) for AAC and FLAC alike.
 * AAC's page: profile, sample rate, channels, bitrate and encoder; FLAC's: the compression effort.
 */
@Composable
fun RecordingFormatPages(page: FormatPage, state: ModelsState, actions: ModelsActions, open: (FormatPage) -> Unit, back: () -> Unit) {
    when (page) {
        FormatPage.Format -> FormatList(state, actions, open, back)
        FormatPage.Aac, FormatPage.Flac -> CodecPage(state, actions, aac = page == FormatPage.Aac, back)
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
private fun encodeLine(state: ModelsState): String = when (state.audioFormat) {
    AudioFormat.WavThenAac, AudioFormat.WavThenFlac ->
        stringResource(if (state.encodeAfter == EncodeAfter.Stop) R.string.rec_format_later_stop_short else R.string.rec_format_later_charging_short)
    else -> stringResource(R.string.rec_format_while_recording_short)
}

/** The Recording format row's line in Settings: the codec, its settings, and when it encodes. */
@Composable
fun recordingFormatSummary(state: ModelsState): String = when (codecOf(state.audioFormat)) {
    AudioFormat.AacLive -> stringResource(R.string.rec_format_codec_aac) + " · " + aacLine(state) + " · " + encodeLine(state)
    AudioFormat.FlacLive -> stringResource(R.string.rec_format_summary_flac, state.flacLevel, FLAC_LEVELS.last) + " · " + encodeLine(state)
    else -> stringResource(R.string.rec_format_summary_wav)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatTopBar(title: String, back: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
        title = { Text(title) },
    )
}

@Composable
private fun FormatList(state: ModelsState, actions: ModelsActions, open: (FormatPage) -> Unit, back: () -> Unit) {
    val codec = codecOf(state.audioFormat)
    Scaffold(topBar = { FormatTopBar(stringResource(R.string.rec_format_format), back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).selectableGroup()) {
            FormatPageNote(stringResource(R.string.rec_format_format_note))
            // WAV: chosen here. AAC and FLAC: chosen and their page opened (the previous way they recorded is kept).
            CodecRow(codec == AudioFormat.Wav, stringResource(R.string.rec_format_codec_wav), stringResource(R.string.rec_format_wav_short), page = false) {
                actions.setAudioFormat(AudioFormat.Wav)
            }
            CodecRow(codec == AudioFormat.AacLive, stringResource(R.string.rec_format_codec_aac),
                if (codec == AudioFormat.AacLive) aacLine(state) + " · " + encodeLine(state) else stringResource(R.string.rec_format_aac_short), page = true) {
                if (codec != AudioFormat.AacLive) actions.setAudioFormat(if (state.audioFormat == AudioFormat.WavThenFlac) AudioFormat.WavThenAac else AudioFormat.AacLive)
                open(FormatPage.Aac)
            }
            CodecRow(codec == AudioFormat.FlacLive, stringResource(R.string.rec_format_codec_flac),
                if (codec == AudioFormat.FlacLive) stringResource(R.string.rec_format_flac_line, state.flacLevel, FLAC_LEVELS.last) + " · " + encodeLine(state)
                else stringResource(R.string.rec_format_flac_short), page = true) {
                if (codec != AudioFormat.FlacLive) actions.setAudioFormat(if (state.audioFormat == AudioFormat.WavThenAac) AudioFormat.WavThenFlac else AudioFormat.FlacLive)
                open(FormatPage.Flac)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            EncodeLater(state, actions)
        }
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
    val on = state.audioFormat == AudioFormat.WavThenAac || state.audioFormat == AudioFormat.WavThenFlac
    val enabled = codec != AudioFormat.Wav
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, enabled = enabled, role = Role.Checkbox) { checked ->
            actions.setAudioFormat(when (codec) {
                AudioFormat.AacLive -> if (checked) AudioFormat.WavThenAac else AudioFormat.AacLive
                else -> if (checked) AudioFormat.WavThenFlac else AudioFormat.FlacLive
            })
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

/** AAC's or FLAC's page: the codec's settings (whether it encodes live or afterwards is on Recording format). */
@Composable
private fun CodecPage(state: ModelsState, actions: ModelsActions, aac: Boolean, back: () -> Unit) {
    val live = if (aac) AudioFormat.AacLive else AudioFormat.FlacLive
    val later = if (aac) AudioFormat.WavThenAac else AudioFormat.WavThenFlac
    val chosen = state.audioFormat == live || state.audioFormat == later
    Scaffold(topBar = { FormatTopBar(stringResource(if (aac) R.string.rec_format_codec_aac else R.string.rec_format_codec_flac), back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (!chosen) {
                // Opened while another codec records: the settings apply once this one is chosen.
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rec_format_not_chosen), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { actions.setAudioFormat(live) }) { Text(stringResource(R.string.rec_format_use)) }
                }
            }
            if (state.audioFormat == later) FormatPageNote(stringResource(if (state.encodeAfter == EncodeAfter.Stop) R.string.rec_format_later_here_stop else R.string.rec_format_later_here_charging))
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (aac) AacSettingsPart(state, actions) else FlacEffortPart(state.flacLevel, if (state.audioFormat == AudioFormat.WavThenFlac) state.audioFormat else AudioFormat.FlacLive, actions.setFlacLevel)
        }
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
        val profiles = listOf(AacProfile.Lc to R.string.rec_format_profile_lc, AacProfile.He to R.string.rec_format_profile_he, AacProfile.HeV2 to R.string.rec_format_profile_he2)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            profiles.forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = aac.profile == p, onClick = { actions.setAac(aac.copy(profile = p).valid()) },
                    shape = SegmentedButtonDefaults.itemShape(i, profiles.size),
                ) { Text(stringResource(label)) }
            }
        }
        FormatNote(stringResource(when (aac.profile) {
            AacProfile.Lc -> R.string.rec_format_profile_lc_note
            AacProfile.He -> R.string.rec_format_profile_he_note
            AacProfile.HeV2 -> R.string.rec_format_profile_he2_note
        }))

        FormatLabel(stringResource(R.string.rec_format_rate))
        val rates = listOf(16_000, 24_000, 48_000)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            rates.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = aac.sampleRate == r, onClick = { actions.setAac(aac.copy(sampleRate = r).valid()) },
                    enabled = r in aac.sampleRates, shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                ) { Text(stringResource(R.string.rec_format_khz, r / 1000)) }
            }
        }
        FormatNote(stringResource(R.string.rec_format_rate_note))

        FormatLabel(stringResource(R.string.rec_format_channels))
        val channels = listOf(1 to R.string.rec_format_mono, 2 to R.string.rec_format_stereo)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            channels.forEachIndexed { i, (c, label) ->
                SegmentedButton(
                    selected = aac.channels == c, onClick = { actions.setAac(aac.copy(channels = c).valid()) },
                    enabled = c in aac.channelCounts, shape = SegmentedButtonDefaults.itemShape(i, channels.size),
                ) { Text(stringResource(label)) }
            }
        }
        FormatNote(stringResource(R.string.rec_format_channels_note))

        Row(Modifier.padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rec_format_bitrate), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.rec_format_size, aac.megabytesPerHour), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            aac.bitrates.forEach { kbps ->
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
