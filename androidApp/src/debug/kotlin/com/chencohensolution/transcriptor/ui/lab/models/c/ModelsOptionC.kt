package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.speech.ModelStatus

/**
 * Settings, then Settings › Models set up for you: a tab per language with the setup recommended for this phone (the
 * live model, the refine model and the speaker models, what is left to download, one button for all of it, one
 * progress bar). "Choose yourself" opens the list: the live and the refine models as radio buttons, each with its
 * download.
 */
@Composable
fun ModelsScreenC(state: ModelsState, actions: ModelsActions) {
    var models by rememberSaveable { mutableStateOf(false) }
    if (models) {
        BackHandler { models = false }
        SetupC(state, actions) { models = false }
    } else {
        SettingsC(state, actions) { models = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsC(state: ModelsState, actions: ModelsActions, openModels: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.notebooks_settings)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ModelsAudioFormat(state, actions)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            ModelsAfterLecture(state, actions)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable(onClick = openModels).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.models_title), style = MaterialTheme.typography.bodyLarge)
                    state.languages.forEach { language ->
                        val ready = state.recommended(language).filter { state.afterLecture || it.fitsLive }.all { state.ready(it) }
                        Text(
                            languageName(language) + " · " + if (ready) stringResource(R.string.models_c_set_up) else stringResource(R.string.models_c_not_set_up),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupC(state: ModelsState, actions: ModelsActions, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val language = state.languages[tab]
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                    title = { Text(stringResource(R.string.models_title)) },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    state.languages.forEachIndexed { i, l -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(languageName(l)) }) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            RecommendedC(language, state, actions)
            TextButton(onClick = { custom = !custom }, Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.models_c_choose))
                Icon(if (custom) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (custom) {
                ModelsSectionTitle(stringResource(R.string.models_job_live), Modifier.padding(top = 0.dp))
                state.liveModels(language).forEach { m ->
                    ChoiceRowC(m, chosen = state.liveFor(language) == m, state, actions) { actions.useLive(language, m) }
                }
                if (state.afterLecture) {
                    ModelsSectionTitle(stringResource(R.string.models_job_refine))
                    state.refineModels(language).forEach { m ->
                        ChoiceRowC(m, chosen = state.refineFor(language) == m, state, actions) { actions.useRefine(language, m) }
                    }
                }
            }
            ModelsStorageLine(state, Modifier.padding(top = 16.dp, bottom = 24.dp))
        }
    }
}

@Composable
private fun RecommendedC(language: String, state: ModelsState, actions: ModelsActions) {
    val setup = state.recommended(language).filter { state.afterLecture || it.fitsLive || it in speakerModels }
    val missing = setup.filter { state.status(it) == ModelStatus.Missing || state.status(it) is ModelStatus.Failed }
    val running = setup.filter { state.status(it) is ModelStatus.Downloading || state.status(it) == ModelStatus.Checking || state.status(it) == ModelStatus.WaitingForWifi }
    val done = setup.all { state.ready(it) }
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (done) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.models_c_recommended), style = MaterialTheme.typography.titleMedium)
            setup.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle, null, Modifier.size(20.dp),
                        tint = if (state.ready(m)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            when {
                                m in speakerModels -> stringResource(R.string.models_job_speakers)
                                m.fitsLive -> stringResource(R.string.models_job_live)
                                else -> stringResource(R.string.models_job_refine)
                            },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        )
                        Text(m.label, style = MaterialTheme.typography.bodyMedium)
                        if (m.npu) ModelsBadge(stringResource(R.string.models_npu_long), Modifier.padding(top = 2.dp))
                    }
                    if (!state.ready(m)) Text(modelsSize(m.sizeBytes), style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                done -> Text(stringResource(R.string.models_c_all_ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                running.isNotEmpty() -> {
                    val total = (running + missing).sumOf { it.sizeBytes }.toFloat()
                    val got = running.sumOf { m -> (when (val s = state.status(m)) { is ModelStatus.Downloading -> s.progress; ModelStatus.Checking -> 1f; else -> 0f } * m.sizeBytes).toLong() }
                    Text(stringResource(R.string.models_c_downloading, modelsSize(got), modelsSize(total.toLong())), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { got / total }, modifier = Modifier.fillMaxWidth())
                    if (running.any { state.status(it) == ModelStatus.WaitingForWifi }) Text(stringResource(R.string.model_waiting_wifi), style = MaterialTheme.typography.bodySmall)
                }
                else -> Button(onClick = { missing.forEach(actions.download) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.models_c_download_all, modelsSize(missing.sumOf { it.sizeBytes })))
                }
            }
        }
    }
}

@Composable
private fun ChoiceRowC(model: SpeechModel, chosen: Boolean, state: ModelsState, actions: ModelsActions, onChoose: () -> Unit) {
    val status = state.status(model)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = status == ModelStatus.Ready, onClick = onChoose).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = chosen, onClick = null, enabled = status == ModelStatus.Ready)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.label, style = MaterialTheme.typography.bodyLarge)
            Text(modelFacts(model), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
            if (model.npu) ModelsBadge(stringResource(R.string.models_npu_long))
            ModelsProgress(status)
        }
        if (status == ModelStatus.Ready) TextButton(onClick = { actions.delete(model) }) { Text(stringResource(R.string.model_delete)) }
        else ModelsDownloadControl(model, status, actions)
    }
}
