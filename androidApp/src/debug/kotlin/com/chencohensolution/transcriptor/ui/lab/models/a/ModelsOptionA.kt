package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.chencohensolution.transcriptor.Recording
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.speech.ModelStatus

private enum class Job { Live, Improve }

/**
 * Option a, Settings with a Transcription section: for each language in use, a Live row and an Improve-with row
 * that say which model runs, each opening a sheet to pick it (downloaded first, the others with their download). The
 * one place a model is chosen for a job; Manage models is a library (downloads, Hugging Face) without that choice.
 * "Improve after the lecture" becomes two switches, and improving by hand is always on the lecture's menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenA(state: ModelsStateA, actions: ModelsActionsA) {
    val m = state.models
    var picking by remember { mutableStateOf<Pair<String, Job>?>(null) }
    picking?.let { (language, job) -> PickerSheet(m, actions, language, job) { picking = null } }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.models.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.notebooks_settings)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.models_a_transcription))
            m.languages.forEach { language ->
                JobRow(m, actions, language, Job.Live) { picking = language to Job.Live }
                JobRow(m, actions, language, Job.Improve) { picking = language to Job.Improve }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SwitchRow(stringResource(R.string.models_a_find_speakers), stringResource(R.string.models_a_find_speakers_note), state.findSpeakersOnStop, actions.setFindSpeakersOnStop)
            SwitchRow(
                stringResource(R.string.models_a_auto_improve),
                stringResource(if (state.autoImprove) R.string.models_a_auto_improve_on else R.string.models_a_auto_improve_off),
                state.autoImprove, actions.setAutoImprove,
            )
            NavRow(stringResource(R.string.models_a_manage), stringResource(R.string.models_a_manage_note)) { actions.openLibrary(null) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.models_a_recordings))
            NavRow(
                stringResource(R.string.folder_title),
                when (val f = m.folder) {
                    is Recording.Folder.Ready -> f.name
                    else -> stringResource(R.string.models_a_folder_in_app)
                },
                onClick = actions.models.chooseFolder,
            )
            SectionTitle(stringResource(R.string.start_format))
            Column(Modifier.selectableGroup()) {
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
                        Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

private fun ModelsState.candidates(language: String, job: Job) = when (job) {
    Job.Live -> liveModels(language)
    Job.Improve -> refineModels(language)
}.let { all -> all.filter { ready(it) } + all.filter { !ready(it) } }

private fun ModelsState.chosen(language: String, job: Job) = when (job) {
    Job.Live -> liveFor(language)
    Job.Improve -> refineFor(language)
}

private val SpeechModel.onNpu get() = kind == ModelKind.WhisperNpu || kind == ModelKind.StreamingTransducerNpu

/** "Hebrew · Live": the model that runs, or that none is downloaded with the one to get. */
@Composable
private fun JobRow(m: ModelsState, actions: ModelsActionsA, language: String, job: Job, onClick: () -> Unit) {
    val chosen = m.chosen(language, job)
    val suggest = if (chosen == null) m.candidates(language, job).firstOrNull() else null
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageTag(languageName(language))
                Text(stringResource(if (job == Job.Live) R.string.models_a_live else R.string.models_a_improve_with), style = MaterialTheme.typography.bodyLarge)
            }
            when {
                chosen != null -> Text(
                    listOfNotNull(isolated(chosen.name), stringResource(R.string.models_a_on_npu).takeIf { chosen.onNpu }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(stringResource(R.string.models_a_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        when {
            suggest != null && m.status(suggest) == ModelStatus.Missing -> FilledTonalButton(onClick = { actions.models.download(suggest) }) {
                Text(stringResource(R.string.models_a_get, modelsSize(suggest.sizeBytes)))
            }
            suggest != null && m.status(suggest) is ModelStatus.Downloading ->
                CircularProgressIndicator(progress = { (m.status(suggest) as ModelStatus.Downloading).progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            else -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** The models that can do [job] for [language]: the downloaded ones to pick, then the others with their download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(m: ModelsState, actions: ModelsActionsA, language: String, job: Job, onDismiss: () -> Unit) {
    val all = m.candidates(language, job)
    val chosen = m.chosen(language, job)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (job == Job.Live) R.string.models_a_live_title else R.string.models_a_improve_title, languageName(language)),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(if (job == Job.Live) R.string.models_a_live_note else R.string.models_a_improve_note, languageName(language)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                all.filter { m.ready(it) }.forEach { model ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen == model, role = Role.RadioButton) {
                            if (job == Job.Live) actions.models.useLive(language, model) else actions.models.useRefine(language, model)
                            onDismiss()
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == model, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                        ModelText(model, m)
                    }
                }
            }
            val failing = all.filter { m.status(it) == ModelStatus.Ready && !m.ready(it) }
            val rest = all.filter { m.status(it) != ModelStatus.Ready }
            if (failing.isNotEmpty() || rest.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.models_a_not_downloaded), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            failing.forEach { model ->
                Row(Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(isolated(model.name), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((m.npu[model.id] as? NpuCheck.Fails)?.reason.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            rest.forEach { model ->
                Row(Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModelText(model, m, Modifier.weight(1f))
                    when (val s = m.status(model)) {
                        is ModelStatus.Downloading -> CircularProgressIndicator(progress = { s.progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        ModelStatus.Checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        ModelStatus.WaitingForWifi -> Text(stringResource(R.string.model_waiting_wifi), style = MaterialTheme.typography.labelSmall)
                        else -> TextButton(onClick = { actions.models.download(model) }) { Text(stringResource(R.string.models_a_get, modelsSize(model.sizeBytes))) }
                    }
                }
            }
            TextButton(onClick = { onDismiss(); actions.openLibrary(language) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.models_a_more))
            }
        }
    }
}

@Composable
private fun ModelText(model: SpeechModel, m: ModelsState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(isolated(model.name), style = MaterialTheme.typography.bodyLarge)
        val facts = listOfNotNull(
            stringResource(R.string.models_a_on_npu).takeIf { model.onNpu && m.npu[model.id] == NpuCheck.Runs },
            stringResource(R.string.models_a_streaming).takeIf { model.kind == ModelKind.StreamingTransducer || model.kind == ModelKind.StreamingTransducerNpu },
            stringResource(R.string.models_a_many).takeIf { SpeechModel.MULTILINGUAL in model.languages },
            modelsSize(model.sizeBytes),
        )
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LanguageTag(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
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
