package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.HubSearch
import com.chencohensolution.transcriptor.models.SpeechModel

/**
 * Option b: Speakers on Settings itself. Under Languages, a Speakers section shows everything at once: the voice
 * model (a row naming it, a sheet to change it), the fewer ↔ more slider right there, the turns model (a row and a
 * sheet), and the pass after Stop. No extra page; Manage models is a library only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenB(state: ModelsState, actions: ModelsActions, @Suppress("UNUSED_PARAMETER") hub: HubSearch) {
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    if (language != null) {
        BackHandler { language = null }
        LabLanguagePage(state, actions, language!!) { language = null }
        return
    }
    val voice = state.labVoiceModel()
    val turns = state.labTurnModel()
    when (sheet) {
        "voices" -> BSheet(stringResource(R.string.models_lab_voices_title), stringResource(R.string.models_lab_voices_note), { sheet = null }) {
            LabSpeakerChoices(state, actions, state.labVoiceModels(), voice) { actions.useVoicePrints(it); sheet = null }
        }
        "turns" -> BSheet(stringResource(R.string.models_lab_turns_title), stringResource(R.string.models_lab_turns_note), { sheet = null }) {
            LabSpeakerChoices(state, actions, state.labTurnModels(), turns) { actions.useSegmentation(it); sheet = null }
        }
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
            LabSectionTitle(stringResource(R.string.models_speakers))
            LabSummaryRow(stringResource(R.string.models_lab_voice_model), listOf(bName(voice, R.string.models_lab_none))) { sheet = "voices" }
            if (voice != null) LabSpeakerTuning(state.speakerTuning[voice.id] ?: 0f, Modifier.padding(horizontal = 20.dp)) { actions.setSpeakerTuning(voice, it) }
            LabSummaryRow(stringResource(R.string.models_lab_turns_title), listOf(bName(turns, R.string.models_lab_none))) { sheet = "turns" }
            LabSwitchRow(stringResource(R.string.models_lab_pass_again), stringResource(R.string.settings_find_speakers_note), state.findSpeakersOnStop, actions.setFindSpeakersOnStop)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.settings_after))
            LabAutoImprove(state, actions)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.settings_storage))
            LabStorage(state, actions, openCatalog = {})
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun bName(model: SpeechModel?, none: Int): Pair<String, Boolean> =
    if (model != null) isolated(model.name) to false else stringResource(none) to true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BSheet(title: String, note: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            LabPageNote(note)
            content()
        }
    }
}
