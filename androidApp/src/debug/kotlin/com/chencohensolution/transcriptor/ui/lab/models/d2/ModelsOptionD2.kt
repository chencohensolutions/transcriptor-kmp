package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.HubSearch
import com.chencohensolution.transcriptor.models.SpeechModel

/**
 * Option d2 (the owner's ask: the speaker models on a screen of their own, not a list on Settings): as option d, but
 * each speaker section has one row with the chosen model (Voice model, Turn model) that opens a page to pick it, like
 * a language's page. The fewer ↔ more slider stays on Settings, under the Voice model row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenD2(state: ModelsState, actions: ModelsActions, @Suppress("UNUSED_PARAMETER") hubSearch: HubSearch) {
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var hub by rememberSaveable { mutableStateOf<String?>(null) }
    val added = remember { mutableStateListOf<D2HubModel>() }
    if (language != null) {
        BackHandler { language = null }
        LabLanguagePage(state, actions, language!!) { language = null }
        return
    }
    val voice = state.labVoiceModel()
    val turns = state.labTurnModel()
    if (hub != null) {
        BackHandler { hub = null }
        D2HubPage(hub!!, added, onAdd = { added += it }) { hub = null }
        return
    }
    // Speaker recognition and Speaker turns share one page: only their data differs.
    if (page != null) {
        BackHandler { page = null }
        val voices = page == "voices"
        D2ModelPage(
            title = stringResource(if (voices) R.string.models_lab_recognition else R.string.models_lab_turns),
            note = stringResource(if (voices) R.string.models_lab_voices_note else R.string.models_lab_turns_note),
            state = state, actions = actions,
            models = if (voices) state.labVoiceModels() else state.labTurnModels(),
            chosen = if (voices) voice else turns,
            pick = if (voices) actions.useVoicePrints else actions.useSegmentation,
            added = added.filter { it.kind == page },
            onFindMore = { hub = page },
            onBack = { page = null },
        )
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.notebooks_settings)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            LabLanguages(state, openLanguage = { language = it }, addLanguage = { actions.addLanguage("fr") })
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.models_lab_recognition))
            LabPageNote(stringResource(R.string.models_lab_voices_note))
            LabSummaryRow(stringResource(R.string.models_lab_voice_model), listOf(d2Line(voice, state))) { page = "voices" }
            if (voice != null) LabSpeakerTuning(state.speakerTuning[voice.id] ?: 0f, Modifier.padding(horizontal = 20.dp)) { actions.setSpeakerTuning(voice, it) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.models_lab_turns))
            LabPageNote(stringResource(R.string.models_lab_turns_note))
            LabSummaryRow(stringResource(R.string.models_lab_turn_model), listOf(d2Line(turns, state))) { page = "turns" }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.settings_after))
            LabSwitchRow(stringResource(R.string.models_lab_pass_again), stringResource(R.string.settings_find_speakers_note), state.findSpeakersOnStop, actions.setFindSpeakersOnStop)
            LabAutoImprove(state, actions)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.settings_storage))
            LabStorage(state, actions, openCatalog = {})
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The chosen model's name and facts ("3D-Speaker CAM++ · 7M"), or a red "No model downloaded". */
@Composable
private fun d2Line(model: SpeechModel?, state: ModelsState): Pair<String, Boolean> {
    if (model == null) return stringResource(R.string.models_lab_none) to true
    val facts = listOfNotNull(
        isolated(model.name),
        isolated(model.parameters),
        stringResource(R.string.models_lab_npu).takeIf { model.id in state.npuVoiceModels },
        stringResource(R.string.models_by_hand).takeIf { model.manual },
    )
    return facts.joinToString(" · ") to false
}

/**
 * A speaker job's page (recognition or turns, the same layout): its note, its models to pick (the ready ones with a
 * radio, the others with their download; picking marks the model and stays here), the repos added from Hugging Face,
 * then Find more on Hugging Face.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun D2ModelPage(
    title: String, note: String, state: ModelsState, actions: ModelsActions, models: List<SpeechModel>, chosen: SpeechModel?,
    pick: (SpeechModel) -> Unit, added: List<D2HubModel>, onFindMore: () -> Unit, onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(title) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            LabPageNote(note)
            LabSpeakerChoices(state, actions, models, chosen, pick)
            D2Added(added)
            D2FindMore(onFindMore)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A Hugging Face repo the search page offers (faked: the app's Hub search has no speaker kinds yet). */
internal data class D2HubModel(val kind: String, val repo: String, val license: String, val size: String) {
    val nonCommercial get() = "-NC" in license
}

private val d2HubModels = listOf(
    D2HubModel("voices", "Wespeaker/wespeaker-voxceleb-resnet34-LM", "CC-BY-4.0", "27 MB"),
    D2HubModel("voices", "Wespeaker/wespeaker-voxceleb-ECAPA512", "CC-BY-4.0", "25 MB"),
    D2HubModel("voices", "csukuangfj/speaker-embedding-models", "Apache-2.0", "40 MB"),
    D2HubModel("voices", "nvidia/speakerverification_en_titanet_large", "CC-BY-4.0", "97 MB"),
    D2HubModel("turns", "pyannote/segmentation-3.0", "MIT", "6 MB"),
    D2HubModel("turns", "BUT-FIT/diarizen-wavlm-large-s80-md", "CC-BY-NC-4.0", "1.2 GB"),
    D2HubModel("turns", "BUT-FIT/diarizen-wavlm-base-s80", "CC-BY-NC-4.0", "380 MB"),
)

private val D2Green = Color(0xFF188038)
private val D2Amber = Color(0xFFB26A00)

@Composable
private fun D2FindMore(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.models_lab_find_more))
    }
}

/** Repos added from the search page, shown downloading under the page's models. */
@Composable
private fun D2Added(models: List<D2HubModel>) {
    models.forEach { model ->
        Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)) {
            Text(isolated(model.repo.substringAfter('/')), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.models_lab_downloading, isolated(model.size)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** Search Hugging Face for a speaker job's models: the field filters the faked results by its words. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun D2HubPage(kind: String, added: List<D2HubModel>, onAdd: (D2HubModel) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val results = d2HubModels.filter { it.kind == kind && words.all { w -> w in it.repo.lowercase() } }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.models_from_hub)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LabPageNote(stringResource(if (kind == "voices") R.string.models_lab_recognition else R.string.models_lab_turns))
            results.forEach { model ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(isolated(model.repo), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (model.nonCommercial) R.string.models_license_nc else R.string.models_license_ok, isolated(model.license)),
                            style = MaterialTheme.typography.bodySmall, color = if (model.nonCommercial) D2Amber else D2Green,
                        )
                        Text(isolated(model.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (model in added) Text(stringResource(R.string.models_lab_added), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
                    else TextButton(onClick = { onAdd(model) }) { Text(stringResource(R.string.models_lab_add)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
