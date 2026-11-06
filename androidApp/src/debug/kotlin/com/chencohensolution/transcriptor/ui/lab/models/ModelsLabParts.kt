package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.FLAC_LEVELS
import com.chencohensolution.transcriptor.speech.ModelStatus
import kotlin.math.roundToInt

// Parts the `models` options a, b and c share: copies of ModelsScreen's private pieces (its language page, rows,
// the audio and folder settings) and the new Speakers page. On promotion they fold back into ModelsScreen.kt.

/** The voice-print models the speakers page lists (the catalog's, and those copied by hand once on the phone). */
internal fun ModelsState.labVoiceModels() = speakerPage.filter { it.kind == ModelKind.SpeakerEmbedding }

/** The segmentation models for live speaker turns. */
internal fun ModelsState.labTurnModels() = speakerPage.filter { it.kind == ModelKind.SpeakerSegmentation }

internal fun ModelsState.labVoiceModel() = labVoiceModels().firstOrNull { it.id == voiceModel && ready(it) }
internal fun ModelsState.labTurnModel() = labTurnModels().firstOrNull { it.id == segmentationModel && ready(it) }

/** "3D-Speaker CAM++" → "CAM++", "pyannote segmentation 3.0" → "pyannote": the short name a summary line uses. */
internal fun labShortName(model: SpeechModel) = when {
    model.name.startsWith("pyannote") -> "pyannote"
    else -> model.name.removePrefix("3D-Speaker ").removePrefix("WeSpeaker ")
}

/**
 * The Speakers page, laid out like a language's page: who is speaking (the voice-print model, a list to pick, with
 * the fewer ↔ more slider under it), the turns while recording (the segmentation model), and the pass after Stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabSpeakersPage(state: ModelsState, actions: ModelsActions, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.models_speakers)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            LabSectionTitle(stringResource(R.string.models_lab_voices_title))
            LabPageNote(stringResource(R.string.models_lab_voices_note))
            val voice = state.labVoiceModel()
            LabSpeakerChoices(state, actions, state.labVoiceModels(), voice, actions.useVoicePrints)
            if (voice != null) LabSpeakerTuning(state.speakerTuning[voice.id] ?: 0f, Modifier.padding(horizontal = 20.dp)) { actions.setSpeakerTuning(voice, it) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.models_lab_turns_title))
            LabPageNote(stringResource(R.string.models_lab_turns_note))
            LabSpeakerChoices(state, actions, state.labTurnModels(), state.labTurnModel(), actions.useSegmentation)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.settings_after))
            LabSwitchRow(stringResource(R.string.models_lab_pass_again), stringResource(R.string.settings_find_speakers_note), state.findSpeakersOnStop, actions.setFindSpeakersOnStop)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Speaker models to pick: the ready ones with a radio, then the catalog's others with their download. */
@Composable
internal fun LabSpeakerChoices(state: ModelsState, actions: ModelsActions, all: List<SpeechModel>, chosen: SpeechModel?, pick: (SpeechModel) -> Unit) {
    Column(Modifier.selectableGroup()) {
        all.filter { state.ready(it) }.forEach { model ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = chosen == model, role = Role.RadioButton) { pick(model) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == model, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                LabSpeakerText(model, state)
            }
        }
        all.filter { !state.ready(it) }.forEach { model ->
            Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LabSpeakerText(model, state, Modifier.weight(1f))
                LabGet(model, state.status(model), actions)
            }
        }
    }
}

@Composable
private fun LabSpeakerText(model: SpeechModel, state: ModelsState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(isolated(model.name), style = MaterialTheme.typography.bodyLarge)
        val facts = listOfNotNull(
            isolated(model.parameters),
            stringResource(R.string.models_lab_npu).takeIf { model.id in state.npuVoiceModels },
            stringResource(R.string.models_by_hand).takeIf { model.manual },
        )
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if ("-NC" in model.license) Text(
            stringResource(R.string.models_license_nc, model.license), style = MaterialTheme.typography.bodySmall, color = LabAmber,
        )
    }
}

private val LabAmber = Color(0xFFB26A00)

/** A model's download at the end of its row, as on a language's page. */
@Composable
internal fun LabGet(model: SpeechModel, status: ModelStatus, actions: ModelsActions) {
    when (status) {
        is ModelStatus.Downloading -> CircularProgressIndicator(progress = { status.progress }, modifier = Modifier.padding(horizontal = 8.dp).size(24.dp), strokeWidth = 3.dp)
        ModelStatus.Checking -> CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(24.dp), strokeWidth = 3.dp)
        ModelStatus.WaitingForWifi -> Text(stringResource(R.string.model_waiting_wifi), style = MaterialTheme.typography.labelSmall)
        ModelStatus.Ready -> {}
        is ModelStatus.Failed -> TextButton(onClick = { actions.download(model) }) { Text(stringResource(R.string.models_retry), color = MaterialTheme.colorScheme.error) }
        ModelStatus.Missing -> TextButton(onClick = { actions.download(model) }) {
            Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
            Text(modelsSize(model.sizeBytes), Modifier.padding(start = 4.dp))
        }
    }
}

/** The fewer ↔ more speakers slider of the voice-print model in use (a copy of ModelsScreen's). */
@Composable
internal fun LabSpeakerTuning(value: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(stringResource(R.string.models_speaker_tuning), style = MaterialTheme.typography.bodyMedium)
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange((v * 10).roundToInt() / 10f) }, valueRange = -1f..1f, steps = 19)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.models_speaker_tuning_fewer), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
            Spacer(Modifier.weight(1f))
            if (v != 0f) TextButton(onClick = { v = 0f; onChange(0f) }) { Text(stringResource(R.string.models_speaker_tuning_reset)) }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.models_speaker_tuning_more), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
        }
    }
}

/** The Languages section, as the app has it (the owner likes it): a row per language, then Add a language. */
@Composable
internal fun LabLanguages(state: ModelsState, openLanguage: (String) -> Unit, addLanguage: () -> Unit) {
    LabSectionTitle(stringResource(R.string.settings_languages))
    state.languages.forEach { l ->
        val live = state.liveFor(l)
        val improve = state.refineFor(l)
        LabSummaryRow(
            languageName(l),
            listOf(
                (if (live != null) stringResource(R.string.settings_live_is, isolated(live.name)) else stringResource(R.string.settings_live_none)) to (live == null),
                (if (improve != null) stringResource(R.string.settings_improve_is, isolated(improve.name)) else stringResource(R.string.settings_improve_none)) to (improve == null),
            ),
        ) { openLanguage(l) }
    }
    TextButton(onClick = addLanguage, modifier = Modifier.padding(horizontal = 12.dp)) {
        Icon(Icons.Filled.Add, null)
        Text(stringResource(R.string.models_add_language), Modifier.padding(start = 8.dp))
    }
}

/** The two summary lines of the speaker models in use, as a language row has its live and improve models. */
@Composable
internal fun labSpeakerLines(state: ModelsState): List<Pair<String, Boolean>> {
    val voice = state.labVoiceModel()
    val turns = state.labTurnModel()
    return listOf(
        (if (voice != null) stringResource(R.string.models_lab_voices_is, isolated(labShortName(voice))) else stringResource(R.string.models_lab_voices_none)) to (voice == null),
        (if (turns != null) stringResource(R.string.models_lab_turns_is, isolated(labShortName(turns))) else stringResource(R.string.models_lab_turns_none)) to (turns == null),
    )
}

/** A row with a title, summary lines (red when the flag is set) and an arrow. */
@Composable
internal fun LabSummaryRow(title: String, lines: List<Pair<String, Boolean>>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            lines.forEach { (text, bad) ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

/** Manage models, the lectures folder, the audio format and FLAC's effort: the app's "Models and storage". */
@Composable
internal fun LabStorage(state: ModelsState, actions: ModelsActions, openCatalog: () -> Unit) {
    LabNavRow(stringResource(R.string.settings_manage), stringResource(R.string.settings_manage_note), openCatalog)
    Text(stringResource(R.string.folder_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.folder_in_app), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions.chooseFolder) { Text(stringResource(R.string.folder_choose)) }
    }
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.start_format), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
        listOf(
            AudioFormat.Wav to R.string.format_wav,
            AudioFormat.WavThenFlac to R.string.format_wav_then_flac,
            AudioFormat.FlacLive to R.string.format_flac_live,
        ).forEach { (option, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = state.audioFormat == option, role = Role.RadioButton) { actions.setAudioFormat(option) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.audioFormat == option, onClick = null)
                Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (state.audioFormat != AudioFormat.Wav) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.flac_effort), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.flac_effort_value, state.flacLevel, FLAC_LEVELS.last), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = state.flacLevel.toFloat(),
                onValueChange = { v -> v.roundToInt().let { if (it != state.flacLevel) actions.setFlacLevel(it) } },
                valueRange = FLAC_LEVELS.first.toFloat()..FLAC_LEVELS.last.toFloat(),
                steps = FLAC_LEVELS.last - FLAC_LEVELS.first - 1,
            )
            Row {
                Text(stringResource(R.string.flac_effort_fast), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.flac_effort_small), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun LabAutoImprove(state: ModelsState, actions: ModelsActions) {
    LabSwitchRow(
        stringResource(R.string.settings_auto_improve),
        stringResource(if (state.autoImprove) R.string.settings_auto_improve_on else R.string.settings_auto_improve_off),
        state.autoImprove, actions.setAutoImprove,
    )
}

/** A language's page (a copy of ModelsScreen's): the live and improve models, each a list to pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabLanguagePage(state: ModelsState, actions: ModelsActions, language: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(languageName(language)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            LabSectionTitle(stringResource(R.string.settings_live_title))
            LabPageNote(stringResource(R.string.settings_live_note))
            LabChoices(state, actions, state.liveModels(language), state.liveFor(language)) { actions.useLive(language, it) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LabSectionTitle(stringResource(R.string.settings_improve_title))
            LabPageNote(stringResource(R.string.settings_improve_note))
            LabChoices(state, actions, state.refineModels(language).filter { state.ready(it) } + state.improveToGet(language), state.refineFor(language)) { actions.useRefine(language, it) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LabChoices(state: ModelsState, actions: ModelsActions, all: List<SpeechModel>, chosen: SpeechModel?, pick: (SpeechModel) -> Unit) {
    Column(Modifier.selectableGroup()) {
        all.filter { state.ready(it) }.forEach { model ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = chosen == model, role = Role.RadioButton) { pick(model) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == model, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                LabChoiceText(model, state)
            }
        }
        all.filter { !state.ready(it) }.forEach { model ->
            Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LabChoiceText(model, state, Modifier.weight(1f))
                LabGet(model, state.status(model), actions)
            }
        }
    }
}

@Composable
private fun LabChoiceText(model: SpeechModel, state: ModelsState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(isolated(model.name.replace(" · ", " · ")), style = MaterialTheme.typography.bodyLarge)
        val facts = listOfNotNull(
            stringResource(R.string.settings_streaming).takeIf { model.kind == ModelKind.StreamingTransducer || model.kind == ModelKind.StreamingTransducerNpu },
            stringResource(R.string.models_multilingual).takeIf { SpeechModel.MULTILINGUAL in model.languages },
            modelsSize(model.sizeBytes).takeIf { state.status(model) == ModelStatus.Ready },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LabPageNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

@Composable
internal fun LabSwitchRow(title: String, note: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = set).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun LabNavRow(title: String, note: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
internal fun LabSectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** A small label, as on a model's card. */
@Composable
internal fun LabBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
