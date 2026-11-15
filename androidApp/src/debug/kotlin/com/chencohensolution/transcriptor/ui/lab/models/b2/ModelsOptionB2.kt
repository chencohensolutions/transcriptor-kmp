package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import com.chencohensolution.transcriptor.settings.EncodeAfter
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Arrangement
import com.chencohensolution.transcriptor.settings.AacSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.chencohensolution.transcriptor.settings.FLAC_LEVELS
import kotlin.math.roundToInt

/** Option b2's pages: Settings, Recording format, and AAC's and FLAC's own. */
private enum class PageB2 { Settings, Format, Aac, Flac }

/** The codec a format records to in the end. */
private fun codecOfB2(format: AudioFormat) = when (format) {
    AudioFormat.Wav -> AudioFormat.Wav
    AudioFormat.AacLive, AudioFormat.WavThenAac -> AudioFormat.AacLive
    AudioFormat.FlacLive, AudioFormat.WavThenFlac -> AudioFormat.FlacLive
}

/**
 * Option b2 (b, as hen asked): one row in Settings › Storage opens "Recording format" with WAV, AAC and FLAC. AAC and
 * FLAC each open a page of their own, which starts with "Record as WAV, encode afterwards" (after the moment, or on
 * the charger above 30%), then that codec's settings.
 */
@Composable
fun ModelsScreenB2(state: ModelsOptionB2State, actions: ModelsOptionB2Actions) {
    var page by rememberSaveable { mutableStateOf(PageB2.Settings) }
    when (page) {
        PageB2.Settings -> SettingsB2(state, actions) { page = PageB2.Format }
        PageB2.Format -> {
            BackHandler { page = PageB2.Settings }
            FormatPageB2(state, actions, open = { page = it }) { page = PageB2.Settings }
        }
        PageB2.Aac, PageB2.Flac -> {
            BackHandler { page = PageB2.Format }
            CodecPageB2(state, actions, aac = page == PageB2.Aac) { page = PageB2.Format }
        }
    }
}

@Composable
private fun aacLineB2(state: ModelsOptionB2State): String {
    val aac = state.aac
    val profile = stringResource(when (aac.profile) { AacProfile.Lc -> R.string.models_b2_profile_lc; AacProfile.He -> R.string.models_b2_profile_he; AacProfile.HeV2 -> R.string.models_b2_profile_he2 })
    val channels = stringResource(if (aac.channels == 1) R.string.models_b2_mono else R.string.models_b2_stereo)
    val where = stringResource(if (state.encoder?.hardware == true) R.string.models_b2_summary_hardware else R.string.models_b2_summary_software)
    return stringResource(R.string.models_b2_summary_aac, profile, aac.bitrateKbps, channels, aac.sampleRate / 1000, where)
}

@Composable
private fun whenLineB2(state: ModelsOptionB2State): String = when (state.format) {
    AudioFormat.WavThenAac, AudioFormat.WavThenFlac ->
        stringResource(if (state.encodeAfter == EncodeAfter.Stop) R.string.models_b2_later_stop_short else R.string.models_b2_later_charging_short)
    else -> stringResource(R.string.models_b2_while_recording_short)
}

@Composable
private fun summaryB2(state: ModelsOptionB2State): String = when (codecOfB2(state.format)) {
    AudioFormat.AacLive -> stringResource(R.string.models_b2_codec_aac) + " · " + aacLineB2(state) + " · " + whenLineB2(state)
    AudioFormat.FlacLive -> stringResource(R.string.models_b2_summary_flac, state.flacLevel, FLAC_LEVELS.last) + " · " + whenLineB2(state)
    else -> stringResource(R.string.models_b2_summary_wav)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBarB2(title: String, back: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
        title = { Text(title) },
    )
}

@Composable
private fun SettingsB2(state: ModelsOptionB2State, actions: ModelsOptionB2Actions, open: () -> Unit) {
    Scaffold(topBar = { TopBarB2(stringResource(R.string.notebooks_settings), actions.back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
            )
            Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.models_b2_format), style = MaterialTheme.typography.bodyLarge)
                    Text(summaryB2(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@Composable
private fun FormatPageB2(state: ModelsOptionB2State, actions: ModelsOptionB2Actions, open: (PageB2) -> Unit, back: () -> Unit) {
    val codec = codecOfB2(state.format)
    Scaffold(topBar = { TopBarB2(stringResource(R.string.models_b2_format), back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).selectableGroup()) {
            PageNoteB2(stringResource(R.string.models_b2_format_note))
            // WAV: chosen here. AAC and FLAC: chosen and their page opened (the previous way they recorded is kept).
            CodecRowB2(codec == AudioFormat.Wav, stringResource(R.string.models_b2_codec_wav), stringResource(R.string.models_b2_wav_short), page = false) {
                actions.setFormat(AudioFormat.Wav)
            }
            CodecRowB2(codec == AudioFormat.AacLive, stringResource(R.string.models_b2_codec_aac),
                if (codec == AudioFormat.AacLive) aacLineB2(state) + " · " + whenLineB2(state) else stringResource(R.string.models_b2_aac_short), page = true) {
                if (codec != AudioFormat.AacLive) actions.setFormat(if (state.format == AudioFormat.WavThenFlac) AudioFormat.WavThenAac else AudioFormat.AacLive)
                open(PageB2.Aac)
            }
            CodecRowB2(codec == AudioFormat.FlacLive, stringResource(R.string.models_b2_codec_flac),
                if (codec == AudioFormat.FlacLive) stringResource(R.string.models_b2_summary_flac, state.flacLevel, FLAC_LEVELS.last) + " · " + whenLineB2(state)
                else stringResource(R.string.models_b2_flac_short), page = true) {
                if (codec != AudioFormat.FlacLive) actions.setFormat(if (state.format == AudioFormat.WavThenAac) AudioFormat.WavThenFlac else AudioFormat.FlacLive)
                open(PageB2.Flac)
            }
        }
    }
}

@Composable
private fun CodecRowB2(selected: Boolean, title: String, note: String, page: Boolean, onClick: () -> Unit) {
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

/** AAC's or FLAC's page: "record as WAV, encode afterwards" and when, then the codec's settings. */
@Composable
private fun CodecPageB2(state: ModelsOptionB2State, actions: ModelsOptionB2Actions, aac: Boolean, back: () -> Unit) {
    val live = if (aac) AudioFormat.AacLive else AudioFormat.FlacLive
    val later = if (aac) AudioFormat.WavThenAac else AudioFormat.WavThenFlac
    val chosen = state.format == live || state.format == later
    Scaffold(topBar = { TopBarB2(stringResource(if (aac) R.string.models_b2_codec_aac else R.string.models_b2_codec_flac), back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (!chosen) {
                // Opened while another codec records: the settings apply once this one is chosen.
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.models_b2_not_chosen), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { actions.setFormat(live) }) { Text(stringResource(R.string.models_b2_use)) }
                }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(value = state.format == later, role = Role.Checkbox) { actions.setFormat(if (it) later else live) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.format == later, onCheckedChange = null)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(stringResource(R.string.models_b2_later), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (aac) R.string.models_b2_later_note_aac else R.string.models_b2_later_note_flac),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.format == later) {
                Column(Modifier.selectableGroup().padding(start = 52.dp, end = 20.dp)) {
                    listOf(
                        EncodeAfter.Stop to (R.string.models_b2_later_stop to R.string.models_b2_later_stop_note),
                        EncodeAfter.Charging to (R.string.models_b2_later_charging to R.string.models_b2_later_charging_note),
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
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (aac) AacSettingsB2(state, actions) else FlacEffortB2(state.flacLevel, if (state.format == AudioFormat.WavThenFlac) state.format else AudioFormat.FlacLive, actions.setFlacLevel)
        }
    }
}

@Composable
private fun PageNoteB2(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AacSettingsB2(state: ModelsOptionB2State, actions: ModelsOptionB2Actions) {
    val aac = state.aac
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        LabelB2(stringResource(R.string.models_b2_profile))
        val profiles = listOf(AacProfile.Lc to R.string.models_b2_profile_lc, AacProfile.He to R.string.models_b2_profile_he, AacProfile.HeV2 to R.string.models_b2_profile_he2)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            profiles.forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = aac.profile == p, onClick = { actions.setAac(aac.copy(profile = p).valid()) },
                    shape = SegmentedButtonDefaults.itemShape(i, profiles.size),
                ) { Text(stringResource(label)) }
            }
        }
        NoteB2(stringResource(when (aac.profile) {
            AacProfile.Lc -> R.string.models_b2_profile_lc_note
            AacProfile.He -> R.string.models_b2_profile_he_note
            AacProfile.HeV2 -> R.string.models_b2_profile_he2_note
        }))

        LabelB2(stringResource(R.string.models_b2_rate))
        val rates = listOf(16_000, 24_000, 48_000)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            rates.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = aac.sampleRate == r, onClick = { actions.setAac(aac.copy(sampleRate = r).valid()) },
                    enabled = r in aac.sampleRates, shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                ) { Text(stringResource(R.string.models_b2_khz, r / 1000)) }
            }
        }
        NoteB2(stringResource(R.string.models_b2_rate_note))

        LabelB2(stringResource(R.string.models_b2_channels))
        val channels = listOf(1 to R.string.models_b2_mono, 2 to R.string.models_b2_stereo)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            channels.forEachIndexed { i, (c, label) ->
                SegmentedButton(
                    selected = aac.channels == c, onClick = { actions.setAac(aac.copy(channels = c).valid()) },
                    enabled = c in aac.channelCounts, shape = SegmentedButtonDefaults.itemShape(i, channels.size),
                ) { Text(stringResource(label)) }
            }
        }
        NoteB2(stringResource(R.string.models_b2_channels_note))

        Row(Modifier.padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.models_b2_bitrate), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.models_b2_size, aac.megabytesPerHour), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            aac.bitrates.forEach { kbps ->
                FilterChip(
                    selected = aac.bitrateKbps == kbps, onClick = { actions.setAac(aac.copy(bitrateKbps = kbps)) },
                    label = { Text(stringResource(R.string.models_b2_kbps, kbps)) },
                )
            }
        }
        if (aac.profile != AacProfile.Lc && aac.bitrateKbps > 64) NoteB2(stringResource(R.string.models_b2_bitrate_he_high))

        LabelB2(stringResource(R.string.models_b2_encoder))
        EncoderPickerB2(state, actions)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EncoderPickerB2(state: ModelsOptionB2State, actions: ModelsOptionB2Actions) {
    var open by remember { mutableStateOf(false) }
    val running = state.encoder
    val auto = state.encoders.firstOrNull { it.name == state.autoEncoder }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.aac.encoder == null) stringResource(R.string.models_b2_encoder_auto) else isolated(state.aac.encoder.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (running != null) Text(
                    stringResource(if (running.hardware) R.string.models_b2_encoder_hardware else R.string.models_b2_encoder_software, isolated(running.name)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.models_b2_encoder_auto))
                        if (auto != null) Text(isolated(auto.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = { open = false; actions.setAac(state.aac.copy(encoder = null)) },
            )
            state.encoders.forEach { e ->
                val fits = state.aac.profile in e.profiles
                DropdownMenuItem(
                    enabled = fits,
                    text = {
                        Column {
                            Text(isolated(e.name))
                            Text(
                                stringResource(when {
                                    !fits -> R.string.models_b2_encoder_no_profile
                                    e.hardware -> R.string.models_b2_encoder_kind_hardware
                                    else -> R.string.models_b2_encoder_kind_software
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
    if (state.encoders.none { it.hardware }) NoteB2(stringResource(R.string.models_b2_encoder_none_hardware))
}

/** FLAC's compression level, as the app's Settings has it. */
@Composable
private fun FlacEffortB2(level: Int, format: AudioFormat, set: (Int) -> Unit) {
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
        NoteB2(stringResource(if (format == AudioFormat.FlacLive) R.string.flac_effort_note_live else R.string.flac_effort_note_after))
    }
}

@Composable
private fun LabelB2(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
private fun NoteB2(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
