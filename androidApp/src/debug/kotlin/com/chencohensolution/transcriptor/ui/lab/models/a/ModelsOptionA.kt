package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
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
 * Option a: the audio block in Settings › Storage as one list, AAC added as a fourth way, and the chosen way's
 * settings right under the list (FLAC's effort, or AAC's profile, sample rate, channels, bitrate and encoder).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenA(state: ModelsOptionAState, actions: ModelsOptionAActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.notebooks_settings)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
            )
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.start_format), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                listOf(
                    AudioFormat.AacLive to (R.string.models_a_aac to R.string.models_a_aac_note),
                    AudioFormat.FlacLive to (R.string.format_flac_live to R.string.models_a_flac_live_note),
                    AudioFormat.WavThenFlac to (R.string.format_wav_then_flac to R.string.models_a_wav_then_flac_note),
                    AudioFormat.Wav to (R.string.format_wav to null),
                ).forEach { (option, labels) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = state.format == option, role = Role.RadioButton) { actions.setFormat(option) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.format == option, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(stringResource(labels.first), style = MaterialTheme.typography.bodyMedium)
                            labels.second?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            when (state.format) {
                AudioFormat.AacLive -> AacSettingsA(state, actions)
                AudioFormat.FlacLive, AudioFormat.WavThenFlac -> FlacEffortA(state.flacLevel, state.format, actions.setFlacLevel)
                AudioFormat.Wav -> {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AacSettingsA(state: ModelsOptionAState, actions: ModelsOptionAActions) {
    val aac = state.aac
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        LabelA(stringResource(R.string.models_a_profile))
        val profiles = listOf(AacProfile.Lc to R.string.models_a_profile_lc, AacProfile.He to R.string.models_a_profile_he, AacProfile.HeV2 to R.string.models_a_profile_he2)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            profiles.forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = aac.profile == p, onClick = { actions.setAac(aac.copy(profile = p).valid()) },
                    shape = SegmentedButtonDefaults.itemShape(i, profiles.size),
                ) { Text(stringResource(label)) }
            }
        }
        NoteA(stringResource(when (aac.profile) {
            AacProfile.Lc -> R.string.models_a_profile_lc_note
            AacProfile.He -> R.string.models_a_profile_he_note
            AacProfile.HeV2 -> R.string.models_a_profile_he2_note
        }))

        LabelA(stringResource(R.string.models_a_rate))
        val rates = listOf(16_000, 24_000, 48_000)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            rates.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = aac.sampleRate == r, onClick = { actions.setAac(aac.copy(sampleRate = r).valid()) },
                    enabled = r in aac.sampleRates, shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                ) { Text(stringResource(R.string.models_a_khz, r / 1000)) }
            }
        }
        NoteA(stringResource(R.string.models_a_rate_note))

        LabelA(stringResource(R.string.models_a_channels))
        val channels = listOf(1 to R.string.models_a_mono, 2 to R.string.models_a_stereo)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            channels.forEachIndexed { i, (c, label) ->
                SegmentedButton(
                    selected = aac.channels == c, onClick = { actions.setAac(aac.copy(channels = c).valid()) },
                    enabled = c in aac.channelCounts, shape = SegmentedButtonDefaults.itemShape(i, channels.size),
                ) { Text(stringResource(label)) }
            }
        }
        NoteA(stringResource(R.string.models_a_channels_note))

        Row(Modifier.padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.models_a_bitrate), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.models_a_size, aac.megabytesPerHour), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            aac.bitrates.forEach { kbps ->
                FilterChip(
                    selected = aac.bitrateKbps == kbps, onClick = { actions.setAac(aac.copy(bitrateKbps = kbps)) },
                    label = { Text(stringResource(R.string.models_a_kbps, kbps)) },
                )
            }
        }
        if (aac.profile != AacProfile.Lc && aac.bitrateKbps > 64) NoteA(stringResource(R.string.models_a_bitrate_he_high))

        LabelA(stringResource(R.string.models_a_encoder))
        EncoderPickerA(state, actions)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EncoderPickerA(state: ModelsOptionAState, actions: ModelsOptionAActions) {
    var open by remember { mutableStateOf(false) }
    val running = state.encoder
    val auto = state.encoders.firstOrNull { it.name == state.autoEncoder }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.aac.encoder == null) stringResource(R.string.models_a_encoder_auto) else isolated(state.aac.encoder.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (running != null) Text(
                    stringResource(if (running.hardware) R.string.models_a_encoder_hardware else R.string.models_a_encoder_software, isolated(running.name)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.models_a_encoder_auto))
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
                                    !fits -> R.string.models_a_encoder_no_profile
                                    e.hardware -> R.string.models_a_encoder_kind_hardware
                                    else -> R.string.models_a_encoder_kind_software
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
    if (state.encoders.none { it.hardware }) NoteA(stringResource(R.string.models_a_encoder_none_hardware))
}

/** FLAC's compression level, as the app's Settings has it. */
@Composable
private fun FlacEffortA(level: Int, format: AudioFormat, set: (Int) -> Unit) {
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
        NoteA(stringResource(if (format == AudioFormat.FlacLive) R.string.flac_effort_note_live else R.string.flac_effort_note_after))
    }
}

@Composable
private fun LabelA(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
private fun NoteA(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
