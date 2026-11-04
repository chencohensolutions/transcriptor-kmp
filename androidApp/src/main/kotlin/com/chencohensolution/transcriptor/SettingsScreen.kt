package com.chencohensolution.transcriptor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.speech.ModelStatus
import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine
import kotlinx.coroutines.delay
import androidx.compose.material3.OutlinedButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings, for now: the audio format, the live models' downloads, the passes after the lecture and their models, the
 * speaker models: the old start screen's parts as they were. Settings › Models replaces it once its design is chosen in
 * the UI lab (docs/screens/models.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, chooseFolder: () -> Unit) {
    var format by remember { mutableStateOf(Recording.settings.audioFormat) }
    val folder by Recording.folder.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.notebooks_settings)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LecturesFolder(folder, chooseFolder)
            Text(stringResource(R.string.start_format), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                listOf(
                    AudioFormat.Wav to R.string.format_wav,
                    AudioFormat.WavThenFlac to R.string.format_wav_then_flac,
                    AudioFormat.FlacLive to R.string.format_flac_live,
                ).forEach { (option, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = format == option, role = Role.RadioButton,
                            onClick = { format = option; Recording.settings.audioFormat = option },
                        ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == option, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            LiveModels("he")
            AfterLecture()
            SpeakerModel()
        }
    }
}

/** The live models for [language], each with its download; the first one ready is the one used. */
@Composable
private fun LiveModels(language: String) {
    val models = ModelCatalog.forLanguage(language, SherpaNpuWhisperEngine.chip).filter { it.fitsLive }
    var refresh by remember { mutableStateOf(0) }
    val statuses by produceState(emptyMap<String, ModelStatus>(), refresh) {
        while (true) {
            value = models.associate { it.id to Recording.models.status(it) }
            delay(1_000)
        }
    }
    // Read again when a download finishes or a model is chosen.
    val inUse = remember(statuses, refresh) { liveModelFor(language) }
    Text(stringResource(R.string.start_live_models), style = MaterialTheme.typography.titleSmall)
    models.forEach { model ->
        ModelRow(
            model, statuses[model.id], model == inUse,
            choose = { Recording.settings.setLiveModel(language, model.id); refresh++ },
        ) { refresh++ }
    }
}

/** Whether the passes after the lecture run (the speaker pass, the refine pass); the refine models when they do. */
@Composable
private fun AfterLecture() {
    var on by remember { mutableStateOf(Recording.settings.afterLecture) }
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = { on = it; Recording.settings.afterLecture = it }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.start_after_lecture), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.start_after_lecture_note), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
    if (on) RefineModels("he")
}

/** The refine models for [language] (the refine pass after the lecture); the first one ready is the one used. */
@Composable
private fun RefineModels(language: String) {
    val models = ModelCatalog.forLanguage(language, SherpaNpuWhisperEngine.chip).filter { !it.fitsLive && it.kind == ModelKind.Whisper }
    var refresh by remember { mutableStateOf(0) }
    val statuses by produceState(emptyMap<String, ModelStatus>(), refresh) {
        while (true) {
            value = models.associate { it.id to Recording.models.status(it) }
            delay(1_000)
        }
    }
    val inUse = remember(statuses, refresh) { Recording.refineModels(language).firstOrNull { !it.fitsLive } }
    Text(stringResource(R.string.start_refine_models), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.start_refine_note), style = MaterialTheme.typography.bodySmall)
    models.forEach { model -> ModelRow(model, statuses[model.id], model == inUse, choose = {}) { refresh++ } }
}

/** The voice-print model for speaker labels: labels are on once it is downloaded. */
@Composable
private fun SpeakerModel() {
    val models = listOf(ModelCatalog.voicePrints, ModelCatalog.speakerSegmentation)
    var refresh by remember { mutableStateOf(0) }
    val statuses by produceState(emptyMap<String, ModelStatus>(), refresh) {
        while (true) {
            value = models.associate { it.id to Recording.models.status(it) }
            delay(1_000)
        }
    }
    Text(stringResource(R.string.start_speakers), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.start_speakers_note), style = MaterialTheme.typography.bodySmall)
    models.forEach { model ->
        ModelRow(model, statuses[model.id], inUse = statuses[model.id] == ModelStatus.Ready, choose = {}) { refresh++ }
    }
}

@Composable
private fun ModelRow(model: SpeechModel, status: ModelStatus?, inUse: Boolean, choose: () -> Unit, changed: () -> Unit) {
    var startError by remember { mutableStateOf<String?>(null) }
    val download = { startError = Recording.models.download(model); changed() }
    Column(Modifier.fillMaxWidth()) {
        Text(model.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${model.parameters} · ${model.license}" + when {
                !inUse || !model.kind.transcribes -> ""
                model.fitsLive -> " · " + stringResource(R.string.model_in_use)
                else -> " · " + stringResource(R.string.model_in_use_refine)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        model.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                null -> {}
                ModelStatus.Missing -> TextButton(onClick = download) {
                    Text(stringResource(R.string.model_download, sizeText(model.sizeBytes)))
                }
                is ModelStatus.Failed -> {
                    Text(stringResource(R.string.model_failed, status.reason), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = download) {
                        Text(stringResource(R.string.model_download, sizeText(model.sizeBytes)))
                    }
                }
                is ModelStatus.Downloading, ModelStatus.WaitingForWifi -> {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (status is ModelStatus.Downloading) stringResource(R.string.model_downloading, (status.progress * 100).toInt())
                            else stringResource(R.string.model_waiting_wifi),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (status is ModelStatus.Downloading) LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { Recording.models.cancel(model); changed() }) { Text(stringResource(R.string.model_cancel)) }
                }
                ModelStatus.Checking -> Text(stringResource(R.string.model_checking), style = MaterialTheme.typography.bodySmall)
                ModelStatus.Ready -> {
                    startError = null
                    Text(stringResource(R.string.model_ready), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (!inUse) TextButton(onClick = choose) { Text(stringResource(R.string.model_use_live)) }
                    TextButton(onClick = { Recording.models.delete(model); changed() }) { Text(stringResource(R.string.model_delete)) }
                }
            }
        }
    }
}

private fun sizeText(bytes: Long) = if (bytes >= 1e9) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

/** Where finished lectures are kept: in the app only, in a folder outside it, moving there, or the folder lost. */
@Composable
private fun LecturesFolder(folder: Recording.Folder, choose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.folder_title), style = MaterialTheme.typography.titleSmall)
        when (folder) {
            Recording.Folder.NotSet -> {
                Text(stringResource(R.string.folder_in_app), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = choose) { Text(stringResource(R.string.folder_choose)) }
            }
            is Recording.Folder.Ready -> {
                Text(stringResource(R.string.folder_ready, folder.name), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = choose) { Text(stringResource(R.string.folder_change)) }
            }
            is Recording.Folder.Moving -> {
                Text(stringResource(R.string.folder_moving, folder.name, (folder.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { folder.progress }, modifier = Modifier.fillMaxWidth())
            }
            is Recording.Folder.Lost -> {
                Text(stringResource(R.string.folder_lost, folder.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = choose) { Text(stringResource(R.string.folder_choose_again)) }
            }
        }
    }
}
