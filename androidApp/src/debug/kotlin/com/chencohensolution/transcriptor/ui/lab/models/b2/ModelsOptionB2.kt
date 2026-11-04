package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import com.chencohensolution.transcriptor.models.ModelKind
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

class HubActions(
    /** Searches Hugging Face for models the app can run. */
    val search: (query: String) -> Unit = {},
    /** Adds a model from Hugging Face to the list (pinned to its commit) and starts its download. */
    val install: (HubModel, HubVariant) -> Unit = { _, _ -> },
    /** Takes a model added from Hugging Face off the list, deleting its files. */
    val remove: (SpeechModel) -> Unit = {},
)

/**
 * Option b (Settings, then Settings › Models as a catalog per language) with models from Hugging Face: the catalog's
 * search button finds repos whose files the app can run (whisper.cpp ggml files, sherpa-onnx streaming models),
 * each with its license marked for commercial use, non-commercial or unknown, its languages, downloads and sizes.
 * One installs a size of it, pinned to the repo's commit; it joins the catalog with a "From Hugging Face" badge, and
 * its menu can take it off the list. No storage bar: the phone's free space isn't this screen's business.
 */
@Composable
fun ModelsScreenB2(state: ModelsState, actions: ModelsActions, hub: HubSearch, hubActions: HubActions) {
    var page by rememberSaveable { mutableStateOf(0) }
    when (page) {
        2 -> {
            BackHandler { page = 1 }
            HubSearchB2(state, hub, hubActions) { page = 1 }
        }
        1 -> {
            BackHandler { page = 0 }
            CatalogB2(state, actions, hubActions, onSearch = { page = 2 }) { page = 0 }
        }
        else -> SettingsB2(state, actions) { page = 1 }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsB2(state: ModelsState, actions: ModelsActions, openModels: () -> Unit) {
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
private fun CatalogB2(state: ModelsState, actions: ModelsActions, hubActions: HubActions, onSearch: () -> Unit, onBack: () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(state.languages.first()) }
    val speakers = shown == SPEAKERS
    val models = if (speakers) speakerModels else state.refineModels(shown).sortedByDescending { it.fitsLive }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.models_title)) },
                actions = { IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.models_b2_find)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
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
                ModelCardB2(model, if (speakers) null else shown, state, actions, hubActions)
            }
            if (!speakers) {
                item {
                    TextButton(onClick = onSearch, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Icon(Icons.Filled.Search, null)
                        Text(stringResource(R.string.models_b2_find), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private const val SPEAKERS = "speakers"

@Composable
private fun ModelCardB2(model: SpeechModel, language: String?, state: ModelsState, actions: ModelsActions, hubActions: HubActions) {
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
                    if (model.fromHub) ModelsBadge(stringResource(R.string.models_b2_from_hub))
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
                    if (status == ModelStatus.Ready && !model.fromHub) DropdownMenuItem(text = { Text(stringResource(R.string.model_delete)) }, onClick = { menu = false; actions.delete(model) })
                    if (model.fromHub) DropdownMenuItem(text = { Text(stringResource(R.string.models_b2_remove)) }, onClick = { menu = false; hubActions.remove(model) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.models_b_page)) }, onClick = { menu = false; uri.openUri(model.source) })
                }
            }
        }
    }
}

/** Hugging Face, searched for models the app can run; a result opens its sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubSearchB2(state: ModelsState, hub: HubSearch, actions: HubActions, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(hub.query) }
    var open by remember { mutableStateOf<HubModel?>(null) }
    open?.let { m -> HubSheetB2(m, state, onDismiss = { open = null }) { v -> actions.install(m, v); open = null } }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.models_b2_find)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.models_b2_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { actions.search(query) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    stringResource(R.string.models_b2_search_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (hub.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            hub.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) } }
            items(hub.results, key = { it.repo }) { m -> HubRowB2(m, state) { open = m } }
            if (!hub.searching && hub.query.isNotEmpty()) {
                item {
                    Text(
                        if (hub.results.isEmpty()) stringResource(R.string.models_b2_none, hub.unusable)
                        else stringResource(R.string.models_b2_unusable, hub.unusable),
                        style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubRowB2(m: HubModel, state: ModelsState, onOpen: () -> Unit) {
    val installed = m.variants.count { v -> state.added.any { it.id == m.toSpeechModel(v).id } }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(isolated(m.name), style = MaterialTheme.typography.titleMedium)
        Text(
            listOf(isolated(m.author), stringResource(R.string.models_b2_downloads, grouped(m.downloads)), stringResource(R.string.models_b2_likes, grouped(m.likes))).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LicenseBadgeB2(m)
            ModelsBadge(stringResource(if (m.kind == ModelKind.Whisper) R.string.models_b2_kind_whisper else R.string.models_b2_kind_streaming))
            ModelsBadge(m.languages.map { if (it == SpeechModel.MULTILINGUAL) stringResource(R.string.models_b2_multilingual) else languageName(it) }.joinToString())
            ModelsBadge(
                if (m.variants.size == 1) modelsSize(m.variants.single().sizeBytes)
                else stringResource(R.string.models_b2_sizes, m.variants.size, modelsSize(m.variants.minOf { it.sizeBytes })),
            )
            if (installed > 0) ModelsBadge(stringResource(R.string.models_b2_installed))
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
}

/** 412,000 (412.000 in a German phone). */
private fun grouped(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

/** The license, colored by what it lets the user do: green for commercial use, amber for not, grey for unknown. */
@Composable
private fun LicenseBadgeB2(m: HubModel) {
    val (color, text) = when (m.use) {
        LicenseUse.Commercial -> LocalTranscriptorColors.current.speakers[1] to stringResource(R.string.models_b2_license_ok, m.license ?: "")
        LicenseUse.NonCommercial -> LocalTranscriptorColors.current.speakers[3] to stringResource(R.string.models_b2_license_nc, m.license ?: "")
        LicenseUse.Unknown -> MaterialTheme.colorScheme.outline to stringResource(R.string.models_b2_license_unknown)
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * A repo's sheet: what it is, its license (and what that means), the commit it is pinned to, and its sizes to pick
 * one. Install asks first when the license isn't for commercial use or isn't known.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubSheetB2(m: HubModel, state: ModelsState, onDismiss: () -> Unit, onInstall: (HubVariant) -> Unit) {
    var chosen by remember { mutableStateOf(m.variants.first()) }
    var confirming by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(if (m.use == LicenseUse.NonCommercial) R.string.models_b2_confirm_nc_title else R.string.models_b2_confirm_unknown_title)) },
            text = { Text(stringResource(if (m.use == LicenseUse.NonCommercial) R.string.models_b2_confirm_nc else R.string.models_b2_confirm_unknown)) },
            confirmButton = { TextButton(onClick = { confirming = false; onInstall(chosen) }) { Text(stringResource(R.string.models_b2_install_anyway)) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.model_cancel)) } },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(isolated(m.repo), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LicenseBadgeB2(m)
                ModelsBadge(stringResource(if (m.kind == ModelKind.Whisper) R.string.models_b2_kind_whisper else R.string.models_b2_kind_streaming))
            }
            Text(
                stringResource(
                    when (m.use) {
                        LicenseUse.Commercial -> R.string.models_b2_license_ok_note
                        LicenseUse.NonCommercial -> R.string.models_b2_license_nc_note
                        LicenseUse.Unknown -> R.string.models_b2_license_unknown_note
                    },
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.models_b2_pinned, isolated(m.commit.take(8))), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
            Text(stringResource(R.string.models_b2_choose_size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.selectableGroup()) {
                m.variants.forEach { v ->
                    val installed = state.added.any { it.id == m.toSpeechModel(v).id }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen == v, enabled = !installed, role = Role.RadioButton) { chosen = v }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == v, onClick = null, enabled = !installed)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(isolated(v.label), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(isolated(v.parameters), modelsSize(v.sizeBytes), if (!v.fitsLive) stringResource(R.string.models_b_after_only) else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
                            )
                        }
                        if (installed) ModelsBadge(stringResource(R.string.models_b2_installed))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uri.openUri("https://huggingface.co/${m.repo}") }) { Text(stringResource(R.string.models_b_page)) }
                val already = state.added.any { it.id == m.toSpeechModel(chosen).id }
                Button(onClick = { if (m.use == LicenseUse.Commercial) onInstall(chosen) else confirming = true }, enabled = !already, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.models_b2_install, modelsSize(chosen.sizeBytes)))
                }
            }
        }
    }
}
