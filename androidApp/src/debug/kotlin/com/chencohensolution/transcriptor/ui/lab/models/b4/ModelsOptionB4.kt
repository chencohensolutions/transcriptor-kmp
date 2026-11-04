package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.LaunchedEffect
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

class HubActionsB4(
    /** Searches Hugging Face for speech models, in a language (null: any) and of a kind (null: all). */
    val search: (query: String, language: String?, kind: HubKind?, npuOnly: Boolean) -> Unit = { _, _, _, _ -> },
    /** Adds a model from Hugging Face to the list (pinned to its commit) and starts its download. */
    val install: (HubModel, HubVariant) -> Unit = { _, _ -> },
    /** Takes a model added from Hugging Face off the list, deleting its files. */
    val remove: (SpeechModel) -> Unit = {},
    /** Shows a language in the catalog, beside the notebooks'. */
    val addLanguage: (String) -> Unit = {},
)

/**
 * Option b2 for any language: the catalog's chips are the languages in use, and Add a language shows any language
 * Whisper knows. The Hugging Face search filters by language (the one the catalog shows, to start) and by kind of
 * model: Whisper and streaming ones run in the app now; Parakeet, SenseVoice, Moonshine and Paraformer are listed,
 * marked as not in the app yet (sherpa-onnx's offline recognizer runs them, the app doesn't use it yet). No storage
 * bar.
 */
@Composable
fun ModelsScreenB4(state: ModelsState, actions: ModelsActions, hub: HubSearchB4, hubActions: HubActionsB4, npuChecks: Map<String, NpuCheck>) {
    var page by rememberSaveable { mutableStateOf(0) }
    var shown by rememberSaveable { mutableStateOf(state.languages.first()) }
    when (page) {
        2 -> {
            BackHandler { page = 1 }
            HubSearchB4(state, hub, hubActions, npuChecks, startLanguage = shown.takeIf { it != SPEAKERS_B4 }) { page = 1 }
        }
        1 -> {
            BackHandler { page = 0 }
            CatalogB4(state, actions, hubActions, npuChecks, shown, { shown = it }, onSearch = { page = 2 }) { page = 0 }
        }
        else -> SettingsB4(state, actions) { page = 1 }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsB4(state: ModelsState, actions: ModelsActions, openModels: () -> Unit) {
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
private fun CatalogB4(
    state: ModelsState, actions: ModelsActions, hubActions: HubActionsB4, npuChecks: Map<String, NpuCheck>,
    shown: String, show: (String) -> Unit, onSearch: () -> Unit, onBack: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) LanguagePickerB4(exclude = state.languages, onDismiss = { picking = false }) { hubActions.addLanguage(it); show(it); picking = false }
    val speakers = shown == SPEAKERS_B4
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
                    items(state.languages + SPEAKERS_B4) { id ->
                        FilterChip(
                            selected = shown == id, onClick = { show(id) },
                            label = { Text(if (id == SPEAKERS_B4) stringResource(R.string.models_speakers) else languageName(id)) },
                        )
                    }
                    item {
                        AssistChip(onClick = { picking = true }, label = { Text(stringResource(R.string.models_b3_add_language)) }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                    }
                }
            }
            item {
                val own = !speakers && com.chencohensolution.transcriptor.models.Languages.withOwnModels.contains(shown)
                Text(
                    when {
                        speakers -> stringResource(R.string.start_speakers_note)
                        own -> stringResource(R.string.models_b_language_note)
                        else -> stringResource(R.string.models_b3_multilingual_note, languageName(shown))
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(models, key = { it.id }) { model ->
                ModelCardB4(model, if (speakers) null else shown, state, actions, hubActions, npuChecks[model.id])
            }
            if (!speakers) {
                item {
                    TextButton(onClick = onSearch, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Icon(Icons.Filled.Search, null)
                        Text(stringResource(R.string.models_b3_find_for, languageName(shown)), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private const val SPEAKERS_B4 = "speakers"

@Composable
private fun ModelCardB4(model: SpeechModel, language: String?, state: ModelsState, actions: ModelsActions, hubActions: HubActionsB4, npu: NpuCheck?) {
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
                    when (npu) {
                        NpuCheck.Checking -> ModelsBadge(stringResource(R.string.models_b4_npu_checking))
                        NpuCheck.Runs -> NpuBadgeB4(stringResource(R.string.models_b4_npu_runs), ok = true)
                        is NpuCheck.Fails -> NpuBadgeB4(stringResource(R.string.models_b4_npu_fails), ok = false)
                        null -> {}
                    }
                    if (!model.fitsLive && language != null) ModelsBadge(stringResource(R.string.models_b_after_only))
                    if (SpeechModel.MULTILINGUAL in model.languages && language != null) ModelsBadge(stringResource(R.string.models_b2_multilingual))
                    if (model.fromHub) ModelsBadge(stringResource(R.string.models_b2_from_hub))
                }
                model.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time) }
                if (npu is NpuCheck.Fails) Text(npu.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                ModelsProgress(status)
            }
            ModelsDownloadControl(model, status, actions)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.models_b_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (language != null && status == ModelStatus.Ready && npu !is NpuCheck.Fails) {
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

/** Any language Whisper knows, the phone's first, with a field to find one by name; [exclude] are shown already. */
@Composable
private fun LanguagePickerB4(exclude: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var find by remember { mutableStateOf("") }
    val all = notebookLanguages().filter { it !in exclude }
    val names = all.associateWith { languageName(it) }
    val shown = all.filter { find.isBlank() || names.getValue(it).contains(find.trim(), ignoreCase = true) || it.startsWith(find.trim().lowercase()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_b3_add_language)) },
        text = {
            Column {
                OutlinedTextField(
                    value = find, onValueChange = { find = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.models_b3_find_language)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown) { tag ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(tag) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(names.getValue(tag), Modifier.weight(1f))
                            if (tag in com.chencohensolution.transcriptor.models.Languages.withOwnModels) ModelsBadge(stringResource(R.string.models_b3_own_models))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** Hugging Face, searched for speech models, filtered by language and kind; a result opens its sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubSearchB4(state: ModelsState, hub: HubSearchB4, actions: HubActionsB4, npuChecks: Map<String, NpuCheck>, startLanguage: String?, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(hub.query) }
    var language by rememberSaveable { mutableStateOf(startLanguage) }
    var kind by rememberSaveable { mutableStateOf<HubKind?>(null) }
    var npuOnly by rememberSaveable { mutableStateOf(false) }
    var pickingLanguage by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<HubEntryB4?>(null) }
    LaunchedEffect(language, kind, npuOnly) { actions.search(query, language, kind, npuOnly) }
    if (pickingLanguage) {
        LanguagePickerB4(exclude = emptyList(), onDismiss = { pickingLanguage = false }) { language = it; pickingLanguage = false }
    }
    open?.let { e -> HubSheetB4(e, state, onDismiss = { open = null }) { v -> actions.install(e.model, v); open = null } }
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
                    placeholder = { Text(stringResource(R.string.models_b3_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { actions.search(query, language, kind, npuOnly) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = language != null, onClick = { pickingLanguage = true },
                            label = { Text(language?.let { languageName(it) } ?: stringResource(R.string.models_b3_any_language)) },
                            leadingIcon = { Icon(Icons.Filled.Language, null, Modifier.size(18.dp)) },
                            trailingIcon = if (language != null) ({ Icon(Icons.Filled.Close, stringResource(R.string.models_b3_any_language), Modifier.size(18.dp).clickable { language = null }) }) else null,
                        )
                    }
                    item {
                        // Only for a phone with a Qualcomm NPU the app can use.
                        val chip = state.chip?.takeIf { it.startsWith("SM") }
                        FilterChip(
                            selected = npuOnly, enabled = chip != null, onClick = { npuOnly = !npuOnly },
                            label = { Text(stringResource(R.string.models_b4_npu_filter)) },
                            leadingIcon = { Icon(Icons.Filled.Memory, null, Modifier.size(18.dp)) },
                        )
                    }
                    item { FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text(stringResource(R.string.models_b3_all_kinds)) }) }
                    items(HubKind.entries.toList()) { k ->
                        FilterChip(selected = kind == k, onClick = { kind = if (kind == k) null else k }, label = { Text(stringResource(k.label)) })
                    }
                }
                Text(
                    stringResource(R.string.models_b3_search_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (hub.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            items(hub.results, key = { it.model.repo }) { e -> HubRowB4(e, state) { open = e } }
            if (!hub.searching) {
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
private fun HubRowB4(e: HubEntryB4, state: ModelsState, onOpen: () -> Unit) {
    val m = e.model
    val installed = m.variants.count { v -> state.added.any { it.id == m.toSpeechModel(v).id } }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(isolated(m.name), style = MaterialTheme.typography.titleMedium, color = if (e.kind.runs) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            listOf(isolated(m.author), stringResource(R.string.models_b2_downloads, groupedB4(m.downloads)), stringResource(R.string.models_b2_likes, groupedB4(m.likes))).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LicenseBadgeB4(m)
            ModelsBadge(stringResource(e.kind.label))
            if (!e.kind.runs) ModelsBadge(stringResource(R.string.models_b3_not_yet))
            NpuFitB4(e, state.chip)
            ModelsBadge(languagesOf(m))
            ModelsBadge(
                if (m.variants.size == 1) modelsSize(m.variants.single().sizeBytes)
                else stringResource(R.string.models_b2_sizes, m.variants.size, modelsSize(m.variants.minOf { it.sizeBytes })),
            )
            if (installed > 0) ModelsBadge(stringResource(R.string.models_b2_installed))
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
}

/** "Hebrew", "English, German, French +22", "Many languages". */
@Composable
private fun languagesOf(m: HubModel): String {
    if (SpeechModel.MULTILINGUAL in m.languages) return stringResource(R.string.models_b2_multilingual)
    val names = m.languages.map { languageName(it) }
    return if (names.size <= 3) names.joinToString() else names.take(3).joinToString() + " +" + (names.size - 3)
}

/** 412,000 (412.000 in a German phone). */
private fun groupedB4(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

/** The license, colored by what it lets the user do: green for commercial use, amber for not, grey for unknown. */
@Composable
private fun LicenseBadgeB4(m: HubModel) {
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
 * A repo's sheet: what it is, its languages and license (and what that means), the commit it is pinned to, its sizes to
 * pick one. Install asks first when the license isn't for commercial use or isn't known; a kind the app doesn't run yet
 * says so instead of Add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubSheetB4(e: HubEntryB4, state: ModelsState, onDismiss: () -> Unit, onInstall: (HubVariant) -> Unit) {
    val m = e.model
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LicenseBadgeB4(m)
                ModelsBadge(stringResource(e.kind.label))
                NpuFitB4(e, state.chip)
                ModelsBadge(languagesOf(m))
            }
            e.npu?.let { n ->
                Text(
                    when {
                        n.chip != state.chip -> stringResource(R.string.models_b4_npu_note_other, n.chip, state.chip ?: "?")
                        n.runtime != APP_QNN -> stringResource(R.string.models_b4_npu_note_runtime, n.runtime, APP_QNN)
                        else -> stringResource(R.string.models_b4_npu_note_this, n.chip, n.runtime)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n.chip == state.chip && n.runtime == APP_QNN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
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
            if (!e.kind.runs) {
                Text(stringResource(R.string.models_b3_not_yet_note, stringResource(e.kind.label)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uri.openUri("https://huggingface.co/${m.repo}") }) { Text(stringResource(R.string.models_b_page)) }
                val already = state.added.any { it.id == m.toSpeechModel(chosen).id }
                Button(
                    onClick = { if (m.use == LicenseUse.Commercial) onInstall(chosen) else confirming = true },
                    enabled = e.kind.runs && !already && e.fits(state.chip), modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            !e.kind.runs -> stringResource(R.string.models_b3_not_yet)
                            !e.fits(state.chip) -> stringResource(R.string.models_b4_not_this_phone)
                            else -> stringResource(R.string.models_b2_install, modelsSize(chosen.sizeBytes))
                        },
                    )
                }
            }
        }
    }
}

/** The version of Qualcomm's runtime the app ships (sherpa-onnx's qnn-libs): context binaries must be built with it. */
private const val APP_QNN = "2.40"

/** An NPU build: for this phone's chip (probably runs; checked once downloaded), or for another one. */
@Composable
private fun NpuFitB4(e: HubEntryB4, chip: String?) {
    val n = e.npu ?: return
    when {
        n.chip != chip -> NpuBadgeB4(stringResource(R.string.models_b4_npu_other, n.chip), ok = false)
        n.runtime != APP_QNN -> NpuBadgeB4(stringResource(R.string.models_b4_npu_runtime, n.chip, n.runtime), ok = false)
        else -> NpuBadgeB4(stringResource(R.string.models_b4_npu_this, n.chip), ok = true)
    }
}

@Composable
private fun NpuBadgeB4(text: String, ok: Boolean) {
    val color = if (ok) LocalTranscriptorColors.current.speakers[1] else MaterialTheme.colorScheme.error
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
