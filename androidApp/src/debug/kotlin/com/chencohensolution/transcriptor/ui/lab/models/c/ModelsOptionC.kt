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
 * Option c: a short Settings. Languages as they are, a Speakers row (its page as in a), and one Recording and
 * storage row for the rest (Improve automatically, Manage models, the lectures folder, the audio). The first page
 * fits on the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenC(state: ModelsState, actions: ModelsActions, @Suppress("UNUSED_PARAMETER") hub: HubSearch) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    when {
        page == "speakers" -> { BackHandler { page = null }; LabSpeakersPage(state, actions) { page = null } }
        page == "storage" -> { BackHandler { page = null }; CStoragePage(state, actions) { page = null } }
        language != null -> { BackHandler { language = null }; LabLanguagePage(state, actions, language!!) { language = null } }
        else -> Scaffold(
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
                LabSummaryRow(stringResource(R.string.start_speakers), labSpeakerLines(state)) { page = "speakers" }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                LabNavRow(stringResource(R.string.models_lab_storage_row), stringResource(R.string.models_lab_storage_note)) { page = "storage" }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CStoragePage(state: ModelsState, actions: ModelsActions, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.models_lab_storage_row)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            LabSectionTitle(stringResource(R.string.settings_after))
            LabAutoImprove(state, actions)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.settings_storage))
            LabStorage(state, actions, openCatalog = {})
            Spacer(Modifier.height(16.dp))
        }
    }
}
