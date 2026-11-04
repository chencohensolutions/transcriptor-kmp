package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.speech.ModelStatus

/**
 * Settings, then Settings › Models as a catalog: chips pick a language (or the speaker models), and each model is a
 * card with its facts, badges for what it does now (Live, Refine) and for the NPU, its download, and a menu to use it
 * for live or for refining, delete it or open its page. A bar on top shows the phone's storage.
 */
@Composable
fun ModelsScreenB(state: ModelsState, actions: ModelsActions) {
    var models by rememberSaveable { mutableStateOf(false) }
    if (models) {
        BackHandler { models = false }
        CatalogB(state, actions) { models = false }
    } else {
        SettingsB(state, actions) { models = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsB(state: ModelsState, actions: ModelsActions, openModels: () -> Unit) {
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
                    val missing = state.languages.filter { state.liveFor(it) == null }
                    Text(
                        if (missing.isEmpty()) stringResource(R.string.models_b_summary, modelsSize(state.usedBytes))
                        else stringResource(R.string.models_b_missing, missing.map { languageName(it) }.joinToString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogB(state: ModelsState, actions: ModelsActions, onBack: () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(state.languages.first()) }
    val speakers = shown == SPEAKERS
    val models = if (speakers) speakerModels else state.refineModels(shown).sortedByDescending { it.fitsLive }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.models_title)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                val total = state.usedBytes + state.freeBytes
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(progress = { state.usedBytes.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                }
                ModelsStorageLine(state)
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.languages + SPEAKERS) { id ->
                        FilterChip(
                            selected = shown == id, onClick = { shown = id },
                            label = { Text(if (id == SPEAKERS) stringResource(R.string.models_speakers) else languageName(id)) },
                        )
                    }
                }
            }
            item {
                Text(
                    if (speakers) stringResource(R.string.start_speakers_note) else stringResource(R.string.models_b_language_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(models, key = { it.id }) { model ->
                ModelCardB(model, if (speakers) null else shown, state, actions)
            }
        }
    }
}

private const val SPEAKERS = "speakers"

@Composable
private fun ModelCardB(model: SpeechModel, language: String?, state: ModelsState, actions: ModelsActions) {
    val status = state.status(model)
    var menu by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val live = language != null && state.liveFor(language) == model
    val refine = language != null && state.afterLecture && state.refineFor(language) == model
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.label, style = MaterialTheme.typography.titleMedium)
                Text(modelFacts(model), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (live) ModelsBadge(stringResource(R.string.models_b_badge_live))
                    if (refine) ModelsBadge(stringResource(R.string.models_b_badge_refine))
                    if (language == null && status == ModelStatus.Ready) ModelsBadge(stringResource(R.string.model_ready))
                    if (model.npu) ModelsBadge(stringResource(R.string.models_npu_long))
                    if (!model.fitsLive && language != null) ModelsBadge(stringResource(R.string.models_b_after_only))
                }
                model.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time) }
                ModelsProgress(status)
            }
            ModelsDownloadControl(model, status, actions)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.models_b_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (language != null && status == ModelStatus.Ready) {
                        if (model.fitsLive && !live) DropdownMenuItem(text = { Text(stringResource(R.string.model_use_live)) }, onClick = { menu = false; actions.useLive(language, model) })
                        if (!refine && state.afterLecture) DropdownMenuItem(text = { Text(stringResource(R.string.models_use_refine)) }, onClick = { menu = false; actions.useRefine(language, model) })
                    }
                    if (status == ModelStatus.Ready) DropdownMenuItem(text = { Text(stringResource(R.string.model_delete)) }, onClick = { menu = false; actions.delete(model) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.models_b_page)) }, onClick = { menu = false; uri.openUri(model.source) })
                }
            }
        }
    }
}
