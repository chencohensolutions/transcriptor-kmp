package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
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

/**
 * Option b: one row in Settings › Storage that says how moments are recorded, opening a page of its own: the codec
 * first (AAC, FLAC, WAV), then that codec's settings (FLAC's when and effort, AAC's profile, rate, channels, bitrate
 * and encoder).
 */
@Composable
fun ModelsScreenB(state: ModelsOptionBState, actions: ModelsOptionBActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) {
        BackHandler { open = false }
        FormatPageB(state, actions) { open = false }
    } else {
        SettingsB(state, actions) { open = true }
    }
}

@Composable
private fun summaryB(state: ModelsOptionBState): String = when (state.format) {
    AudioFormat.AacLive -> {
        val aac = state.aac
        val profile = stringResource(when (aac.profile) { AacProfile.Lc -> R.string.models_b_profile_lc; AacProfile.He -> R.string.models_b_profile_he; AacProfile.HeV2 -> R.string.models_b_profile_he2 })
        val channels = stringResource(if (aac.channels == 1) R.string.models_b_mono else R.string.models_b_stereo)
        val where = stringResource(if (state.encoder?.hardware == true) R.string.models_b_summary_hardware else R.string.models_b_summary_software)
        stringResource(R.string.models_b_summary_aac, profile, aac.bitrateKbps, channels, aac.sampleRate / 1000, where)
    }
    AudioFormat.FlacLive -> stringResource(R.string.models_b_summary_flac_live, state.flacLevel, FLAC_LEVELS.last)
    AudioFormat.WavThenFlac -> stringResource(R.string.models_b_summary_flac_after, state.flacLevel, FLAC_LEVELS.last)
    AudioFormat.Wav, AudioFormat.WavThenAac -> stringResource(R.string.models_b_summary_wav)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsB(state: ModelsOptionBState, actions: ModelsOptionBActions, open: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.notebooks_settings)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
            )
            Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.models_b_format), style = MaterialTheme.typography.bodyLarge)
                    Text(summaryB(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatPageB(state: ModelsOptionBState, actions: ModelsOptionBActions, back: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.models_b_format)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                LabelB(stringResource(R.string.models_b_codec))
                val codecs = listOf(
                    AudioFormat.AacLive to R.string.models_b_codec_aac,
                    AudioFormat.FlacLive to R.string.models_b_codec_flac,
                    AudioFormat.Wav to R.string.models_b_codec_wav,
                )
                val codec = if (state.format == AudioFormat.WavThenFlac) AudioFormat.FlacLive else state.format
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    codecs.forEachIndexed { i, (f, label) ->
                        SegmentedButton(
                            selected = codec == f, onClick = { if (codec != f) actions.setFormat(f) },
                            shape = SegmentedButtonDefaults.itemShape(i, codecs.size),
                        ) { Text(stringResource(label)) }
                    }
                }
                NoteB(stringResource(when (codec) {
                    AudioFormat.AacLive -> R.string.models_b_aac_short
                    AudioFormat.Wav -> R.string.models_b_wav_short
                    else -> R.string.models_b_flac_short
                }))
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            when (state.format) {
                AudioFormat.AacLive -> AacSettingsB(state, actions)
                AudioFormat.FlacLive, AudioFormat.WavThenFlac -> {
                    Column(Modifier.selectableGroup().padding(horizontal = 4.dp)) {
                        Text(stringResource(R.string.models_b_flac_when), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                        listOf(
                            AudioFormat.FlacLive to (R.string.models_b_flac_live to R.string.models_b_flac_live_short),
                            AudioFormat.WavThenFlac to (R.string.models_b_flac_after to R.string.models_b_flac_after_short),
                        ).forEach { (option, labels) ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = state.format == option, role = Role.RadioButton) { actions.setFormat(option) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = state.format == option, onClick = null)
                                Column(Modifier.padding(start = 16.dp)) {
                                    Text(stringResource(labels.first), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(labels.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    FlacEffortB(state.flacLevel, state.format, actions.setFlacLevel)
                }
                AudioFormat.Wav, AudioFormat.WavThenAac -> {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AacSettingsB(state: ModelsOptionBState, actions: ModelsOptionBActions) {
    val aac = state.aac
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        LabelB(stringResource(R.string.models_b_profile))
        val profiles = listOf(AacProfile.Lc to R.string.models_b_profile_lc, AacProfile.He to R.string.models_b_profile_he, AacProfile.HeV2 to R.string.models_b_profile_he2)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            profiles.forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = aac.profile == p, onClick = { actions.setAac(aac.copy(profile = p).valid()) },
                    shape = SegmentedButtonDefaults.itemShape(i, profiles.size),
                ) { Text(stringResource(label)) }
            }
        }
        NoteB(stringResource(when (aac.profile) {
            AacProfile.Lc -> R.string.models_b_profile_lc_note
            AacProfile.He -> R.string.models_b_profile_he_note
            AacProfile.HeV2 -> R.string.models_b_profile_he2_note
        }))

        LabelB(stringResource(R.string.models_b_rate))
        val rates = listOf(16_000, 24_000, 48_000)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            rates.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = aac.sampleRate == r, onClick = { actions.setAac(aac.copy(sampleRate = r).valid()) },
                    enabled = r in aac.sampleRates, shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                ) { Text(stringResource(R.string.models_b_khz, r / 1000)) }
            }
        }
        NoteB(stringResource(R.string.models_b_rate_note))

        LabelB(stringResource(R.string.models_b_channels))
        val channels = listOf(1 to R.string.models_b_mono, 2 to R.string.models_b_stereo)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            channels.forEachIndexed { i, (c, label) ->
                SegmentedButton(
                    selected = aac.channels == c, onClick = { actions.setAac(aac.copy(channels = c).valid()) },
                    enabled = c in aac.channelCounts, shape = SegmentedButtonDefaults.itemShape(i, channels.size),
                ) { Text(stringResource(label)) }
            }
        }
        NoteB(stringResource(R.string.models_b_channels_note))

        Row(Modifier.padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.models_b_bitrate), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.models_b_size, aac.megabytesPerHour), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            aac.bitrates.forEach { kbps ->
                FilterChip(
                    selected = aac.bitrateKbps == kbps, onClick = { actions.setAac(aac.copy(bitrateKbps = kbps)) },
                    label = { Text(stringResource(R.string.models_b_kbps, kbps)) },
                )
            }
        }
        if (aac.profile != AacProfile.Lc && aac.bitrateKbps > 64) NoteB(stringResource(R.string.models_b_bitrate_he_high))

        LabelB(stringResource(R.string.models_b_encoder))
        EncoderPickerB(state, actions)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EncoderPickerB(state: ModelsOptionBState, actions: ModelsOptionBActions) {
    var open by remember { mutableStateOf(false) }
    val running = state.encoder
    val auto = state.encoders.firstOrNull { it.name == state.autoEncoder }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.aac.encoder == null) stringResource(R.string.models_b_encoder_auto) else isolated(state.aac.encoder.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (running != null) Text(
                    stringResource(if (running.hardware) R.string.models_b_encoder_hardware else R.string.models_b_encoder_software, isolated(running.name)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.models_b_encoder_auto))
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
                                    !fits -> R.string.models_b_encoder_no_profile
                                    e.hardware -> R.string.models_b_encoder_kind_hardware
                                    else -> R.string.models_b_encoder_kind_software
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
    if (state.encoders.none { it.hardware }) NoteB(stringResource(R.string.models_b_encoder_none_hardware))
}

/** FLAC's compression level, as the app's Settings has it. */
@Composable
private fun FlacEffortB(level: Int, format: AudioFormat, set: (Int) -> Unit) {
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
        NoteB(stringResource(if (format == AudioFormat.FlacLive) R.string.flac_effort_note_live else R.string.flac_effort_note_after))
    }
}

@Composable
private fun LabelB(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
private fun NoteB(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
