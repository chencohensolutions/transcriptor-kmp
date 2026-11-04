package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.settings.AudioFormat

// Settings every Models option has on its first page, drawn the same way: the audio format and the passes after the
// lecture (what the temporary start screen has now).

@Composable
internal fun ModelsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** The audio format, as radio buttons. */
@Composable
internal fun ModelsAudioFormat(state: ModelsState, actions: ModelsActions) {
    ModelsSectionTitle(stringResource(R.string.start_format))
    Column(Modifier.selectableGroup()) {
        listOf(
            AudioFormat.Wav to R.string.format_wav,
            AudioFormat.WavThenFlac to R.string.format_wav_then_flac,
            AudioFormat.FlacLive to R.string.format_flac_live,
        ).forEach { (option, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = state.audioFormat == option, role = Role.RadioButton) { actions.setAudioFormat(option) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.audioFormat == option, onClick = null)
                Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Whether the passes after the lecture run. */
@Composable
internal fun ModelsAfterLecture(state: ModelsState, actions: ModelsActions) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = state.afterLecture, role = Role.Switch, onValueChange = actions.setAfterLecture)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.start_after_lecture), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.models_after_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = state.afterLecture, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** "Models use 2.6 GB · 38.7 GB free on the phone". */
@Composable
internal fun ModelsStorageLine(state: ModelsState, modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.models_storage, modelsSize(state.usedBytes), modelsSize(state.freeBytes)),
        style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
