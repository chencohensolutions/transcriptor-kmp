package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.Recording
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.speech.ModelStatus

/**
 * Option b, a page per language: Settings lists the languages in use, each with what runs live and what improves
 * ("Live: ivrit.ai turbo · Improve: large-v3"); its page has both choices as lists you pick from, the models not
 * downloaded under each with their download. The switches and Manage models stay on Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenB(state: ModelsStateB, actions: ModelsActionsB) {
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    language?.let { l ->
        BackHandler { language = null }
        LanguagePage(state.models, actions, l) { language = null }
        return
    }
    val m = state.models
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.models.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.notebooks_settings)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.models_b_languages))
            m.languages.forEach { l ->
                val live = m.liveFor(l)
                val improve = m.refineFor(l)
                Row(Modifier.fillMaxWidth().clickable { language = l }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(languageName(l), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (live != null) stringResource(R.string.models_b_live_is, isolated(live.name)) else stringResource(R.string.models_b_live_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (live != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            if (improve != null) stringResource(R.string.models_b_improve_is, isolated(improve.name)) else stringResource(R.string.models_b_improve_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (improve != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            TextButton(onClick = { actions.openLibrary(null) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.models_b_add_language)) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.models_b_after))
            SwitchRow(stringResource(R.string.models_b_find_speakers), stringResource(R.string.models_b_find_speakers_note), state.findSpeakersOnStop, actions.setFindSpeakersOnStop)
            SwitchRow(
                stringResource(R.string.models_b_auto_improve),
                stringResource(if (state.autoImprove) R.string.models_b_auto_improve_on else R.string.models_b_auto_improve_off),
                state.autoImprove, actions.setAutoImprove,
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.models_b_storage))
            NavRow(stringResource(R.string.models_b_manage), stringResource(R.string.models_b_manage_note)) { actions.openLibrary(null) }
            NavRow(
                stringResource(R.string.folder_title),
                when (val f = m.folder) {
                    is Recording.Folder.Ready -> f.name
                    else -> stringResource(R.string.models_b_folder_in_app)
                },
                onClick = actions.models.chooseFolder,
            )
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.start_format), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                listOf(
                    AudioFormat.Wav to R.string.format_wav,
                    AudioFormat.WavThenFlac to R.string.format_wav_then_flac,
                    AudioFormat.FlacLive to R.string.format_flac_live,
                ).forEach { (option, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = m.audioFormat == option, role = Role.RadioButton) { actions.models.setAudioFormat(option) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m.audioFormat == option, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** A language's page: what runs live, and what improves its lectures, each a list to pick from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePage(m: ModelsState, actions: ModelsActionsB, language: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(languageName(language)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.models_b_live_title))
            Note(stringResource(R.string.models_b_live_note))
            Choices(m, actions, m.liveModels(language), m.liveFor(language)) { actions.models.useLive(language, it) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.models_b_improve_title))
            Note(stringResource(R.string.models_b_improve_note))
            Choices(m, actions, m.refineModels(language), m.refineFor(language)) { actions.models.useRefine(language, it) }
            TextButton(onClick = { actions.openLibrary(language) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.models_b_more, languageName(language)))
            }
        }
    }
}

@Composable
private fun Choices(m: ModelsState, actions: ModelsActionsB, all: List<SpeechModel>, chosen: SpeechModel?, pick: (SpeechModel) -> Unit) {
    Column(Modifier.selectableGroup()) {
        all.filter { m.ready(it) }.forEach { model ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = chosen == model, role = Role.RadioButton) { pick(model) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == model, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                ModelText(model, m)
            }
        }
        all.filter { !m.ready(it) }.forEach { model ->
            Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ModelText(model, m, Modifier.weight(1f))
                when (val s = m.status(model)) {
                    is ModelStatus.Downloading -> CircularProgressIndicator(progress = { s.progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    ModelStatus.Checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    ModelStatus.WaitingForWifi -> Text(stringResource(R.string.model_waiting_wifi), style = MaterialTheme.typography.labelSmall)
                    ModelStatus.Ready -> Text(stringResource(R.string.models_b_npu_fails), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else -> FilledTonalButton(onClick = { actions.models.download(model) }) { Text(stringResource(R.string.models_b_get, modelsSize(model.sizeBytes))) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

private val SpeechModel.onNpu get() = kind == ModelKind.WhisperNpu || kind == ModelKind.StreamingTransducerNpu

@Composable
private fun ModelText(model: SpeechModel, m: ModelsState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(isolated(model.name), style = MaterialTheme.typography.bodyLarge)
        val facts = listOfNotNull(
            stringResource(R.string.models_b_on_npu).takeIf { model.onNpu && m.npu[model.id] == NpuCheck.Runs },
            stringResource(R.string.models_b_streaming).takeIf { model.kind == ModelKind.StreamingTransducer || model.kind == ModelKind.StreamingTransducerNpu },
            stringResource(R.string.models_b_many).takeIf { SpeechModel.MULTILINGUAL in model.languages },
            modelsSize(model.sizeBytes),
        )
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, note: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = set).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun NavRow(title: String, note: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}
