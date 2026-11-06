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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.HubSearch

/**
 * Option d (the owner's ask: a section to pick the turn model, a section to pick the speaker detection model):
 * Settings has Languages as it is, then **Speaker recognition**, the voice-print models as a list to pick right
 * there with the fewer ↔ more slider under it, then **Speaker turns**, the segmentation models as a list to pick.
 * After a lecture holds Find the speakers again after Stop and Improve automatically. No ⋮ menus pick anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenD(state: ModelsState, actions: ModelsActions, @Suppress("UNUSED_PARAMETER") hub: HubSearch) {
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    if (language != null) {
        BackHandler { language = null }
        LabLanguagePage(state, actions, language!!) { language = null }
        return
    }
    val voice = state.labVoiceModel()
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
            LabSpeakerChoices(state, actions, state.labVoiceModels(), voice, actions.useVoicePrints)
            if (voice != null) LabSpeakerTuning(state.speakerTuning[voice.id] ?: 0f, Modifier.padding(horizontal = 20.dp)) { actions.setSpeakerTuning(voice, it) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.models_lab_turns))
            LabPageNote(stringResource(R.string.models_lab_turns_note))
            LabSpeakerChoices(state, actions, state.labTurnModels(), state.labTurnModel(), actions.useSegmentation)
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
