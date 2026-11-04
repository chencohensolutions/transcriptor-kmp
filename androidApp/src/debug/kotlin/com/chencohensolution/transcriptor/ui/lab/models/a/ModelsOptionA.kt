package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.speech.ModelStatus

/**
 * One Settings page. The audio and the passes on top; then per language a row for each job, live transcription and
 * refining, saying which model does it (or that none is ready); a tap opens the job's models in a sheet, to download,
 * delete or choose one. The speaker models are one row too. The storage the models take is at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenA(state: ModelsState, actions: ModelsActions) {
    var job by remember { mutableStateOf<JobA?>(null) }
    job?.let { j -> JobSheetA(j, state, actions) { job = null } }
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
            ModelsSectionTitle(stringResource(R.string.models_a_after_title))
            ModelsAfterLecture(state, actions)
            state.languages.forEach { language ->
                HorizontalDivider(Modifier.padding(top = 8.dp))
                ModelsSectionTitle(languageName(language))
                JobRowA(stringResource(R.string.models_job_live), state.liveFor(language), state, state.liveModels(language)) { job = JobA.Live(language) }
                if (state.afterLecture) {
                    JobRowA(stringResource(R.string.models_job_refine), state.refineFor(language), state, state.refineModels(language)) { job = JobA.Refine(language) }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            ModelsSectionTitle(stringResource(R.string.models_speakers))
            val speakersReady = speakerModels.all { state.ready(it) }
            JobRowA(
                stringResource(R.string.models_job_speakers), null, state, speakerModels,
                summary = if (speakersReady) stringResource(R.string.models_speakers_ready) else null,
            ) { job = JobA.Speakers }
            ModelsStorageLine(state, Modifier.padding(top = 16.dp, bottom = 24.dp))
        }
    }
}

private sealed interface JobA {
    data class Live(val language: String) : JobA
    data class Refine(val language: String) : JobA
    data object Speakers : JobA
}

/**
 * A job's row: its name, the model doing it or "None downloaded", and a download running among its models.
 */
@Composable
private fun JobRowA(title: String, model: SpeechModel?, state: ModelsState, models: List<SpeechModel>, summary: String? = null, onClick: () -> Unit) {
    val running = models.firstOrNull { state.status(it) is ModelStatus.Downloading || state.status(it) == ModelStatus.Checking }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            val line = summary ?: model?.label
            Text(
                line ?: stringResource(R.string.models_none_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = if (line == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model?.npu == true) ModelsBadge(stringResource(R.string.models_npu))
            running?.let { ModelsProgress(state.status(it)) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobSheetA(job: JobA, state: ModelsState, actions: ModelsActions, onDismiss: () -> Unit) {
    val (title, note, models, inUse) = when (job) {
        is JobA.Live -> Quad(
            stringResource(R.string.models_job_live) + " · " + languageName(job.language), stringResource(R.string.models_live_note),
            state.liveModels(job.language), state.liveFor(job.language),
        )
        is JobA.Refine -> Quad(
            stringResource(R.string.models_job_refine) + " · " + languageName(job.language), stringResource(R.string.start_refine_note),
            state.refineModels(job.language), state.refineFor(job.language),
        )
        JobA.Speakers -> Quad(stringResource(R.string.models_job_speakers), stringResource(R.string.start_speakers_note), speakerModels, null)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            models.forEach { model ->
                val status = state.status(model)
                val choosable = job !is JobA.Speakers
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = choosable && status == ModelStatus.Ready) {
                            when (job) {
                                is JobA.Live -> actions.useLive(job.language, model)
                                is JobA.Refine -> actions.useRefine(job.language, model)
                                JobA.Speakers -> {}
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (choosable) RadioButton(selected = model == inUse, onClick = null, enabled = status == ModelStatus.Ready)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(model.label, style = MaterialTheme.typography.bodyLarge)
                        Text(modelFacts(model), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
                        if (model.npu) ModelsBadge(stringResource(R.string.models_npu_long))
                        model.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time) }
                        ModelsProgress(status)
                    }
                    if (status == ModelStatus.Ready) {
                        TextButton(onClick = { actions.delete(model) }) { Text(stringResource(R.string.model_delete)) }
                    } else {
                        ModelsDownloadControl(model, status, actions)
                    }
                }
            }
        }
    }
}

private data class Quad(val title: String, val note: String, val models: List<SpeechModel>, val inUse: SpeechModel?)
