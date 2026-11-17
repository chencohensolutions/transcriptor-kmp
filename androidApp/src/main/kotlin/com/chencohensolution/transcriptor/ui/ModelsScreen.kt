package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.recording.AacEncoderInfo
import com.chencohensolution.transcriptor.settings.EncodeAfter
import com.chencohensolution.transcriptor.settings.AacSettings
import kotlin.math.roundToInt
import androidx.compose.material3.Slider
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.Recording
import com.chencohensolution.transcriptor.models.HubKind
import com.chencohensolution.transcriptor.models.HubModel
import com.chencohensolution.transcriptor.models.HubSearch
import com.chencohensolution.transcriptor.models.HubVariant
import com.chencohensolution.transcriptor.models.Languages
import com.chencohensolution.transcriptor.models.LicenseUse
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.DefaultModels
import com.chencohensolution.transcriptor.models.ModelChoices
import com.chencohensolution.transcriptor.models.ModelJob
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.ModelRemoval
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.models.fromHub
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.recording.MicInput
import com.chencohensolution.transcriptor.settings.LevelMeterStyle
import com.chencohensolution.transcriptor.settings.ProcessorMeterStyle
import com.chencohensolution.transcriptor.settings.FLAC_DEFAULT_LEVEL
import com.chencohensolution.transcriptor.settings.FLAC_LEVELS
import com.chencohensolution.transcriptor.speech.ModelStatus
import kotlin.math.roundToInt

/** The one-time check of an NPU model once it is downloaded. */
sealed interface NpuCheck {
    data object Checking : NpuCheck
    data object Runs : NpuCheck
    data class Fails(val reason: String) : NpuCheck
}

/** Settings and Settings › Models. */
data class ModelsState(
    /** The languages in use: the notebooks', a lecture in none, the phone's, those added here. */
    val languages: List<String>,
    /** The phone's chip (`Build.SOC_MODEL`): the NPU builds for it are offered. */
    val chip: String?,
    /** The catalog's models and those added from Hugging Face. */
    val models: List<SpeechModel>,
    val statuses: Map<String, ModelStatus>,
    /** The live and refine models chosen per language; a language without one uses its first ready model. */
    val live: Map<String, String> = emptyMap(),
    val refine: Map<String, String> = emptyMap(),
    val npu: Map<String, NpuCheck> = emptyMap(),
    val audioFormat: AudioFormat,
    /** FLAC's compression level ([FLAC_LEVELS]), for both FLAC formats. */
    val flacLevel: Int = FLAC_DEFAULT_LEVEL,
    /** How AAC records, and what "WAV, then AAC" encodes to. */
    val aac: AacSettings = AacSettings(),
    /** FLAC's rate, channels and bit depth (the bit depth also the WAV's before it). */
    val flac: com.chencohensolution.transcriptor.settings.FlacFormat = com.chencohensolution.transcriptor.settings.FlacFormat(),
    /** When "Record as WAV, encode afterwards" encodes. */
    val encodeAfter: EncodeAfter = EncodeAfter.Stop,
    /** When moments recorded with "After" are transcribed. */
    val transcribeWhen: EncodeAfter = EncodeAfter.Charging,
    /** The phone's AAC encoders, hardware first ([com.chencohensolution.transcriptor.recording.AacEncoders.all]). */
    val aacEncoders: List<AacEncoderInfo> = emptyList(),
    /** What "automatic" runs for [aac]: the first of [com.chencohensolution.transcriptor.recording.AacEncoders.candidates]. */
    val autoAacEncoder: String = "",
    /** The speaker pass runs when a lecture stops. */
    val findSpeakersOnStop: Boolean,
    /** Lectures are improved on their own after they stop (improving by hand is always on the lecture's menu). */
    val autoImprove: Boolean = false,
    /** The notification while recording says nothing about it ([com.chencohensolution.transcriptor.settings.SettingsStore.quietRecording]). */
    val quietRecording: Boolean = false,
    /** Stop asks for a second tap, in the app and on the tile ([com.chencohensolution.transcriptor.settings.SettingsStore.stopTwice]). */
    val stopTwice: Boolean = false,
    /** The microphone in use gives more than 16 bits (a USB one may; the phone's own don't): 24 bit is offered. */
    val deepCapture: Boolean = false,
    /** Advanced mode: the main screen's Record bar also shows Quality and Battery. */
    val advancedMode: Boolean = false,
    val folder: Recording.Folder = Recording.Folder.NotSet,
    /** Settings › Server backup's line: off, or on to which server and how many moments wait. */
    val server: String = "",
    /** The voice-print model speaker labels use (its id; [Recording.voiceModel]); null without one. */
    val voiceModel: String? = null,
    /** The voice-print models with an NPU build for this phone in their folder. */
    val npuVoiceModels: Set<String> = emptySet(),
    /** The user's "fewer ↔ more speakers" per voice-print model, -1..1 (0: its tuning). */
    val speakerTuning: Map<String, Float> = emptyMap(),
    /** The segmentation model live speaker turns use (its id); null without one. */
    val segmentationModel: String? = null,
    /** The processor meters on the lecture screen while recording (Advanced). */
    val processorMeters: ProcessorMeterStyle = ProcessorMeterStyle.Off,
    val levelMeter: LevelMeterStyle = LevelMeterStyle.Bars,
    /** The microphone picked to record from; null: the phone's own. */
    val micInput: com.chencohensolution.transcriptor.recording.MicInput? = null,
    /** The microphones connected now besides the phone's own. */
    val micInputs: List<com.chencohensolution.transcriptor.recording.MicInput> = emptyList(),
    /** Recording or improving: models are in use, none is removed or deleted until it is done. */
    val busy: Boolean = false,
) {
    /** The AAC encoder the next AAC moment runs on. */
    val aacEncoder get() = aacEncoders.firstOrNull { it.name == (aac.encoder ?: autoAacEncoder) } ?: aacEncoders.firstOrNull { it.name == autoAacEncoder }

    fun status(model: SpeechModel) = statuses[model.id] ?: ModelStatus.Missing
    fun ready(model: SpeechModel) = status(model) == ModelStatus.Ready && npu[model.id] !is NpuCheck.Fails

    /** The models for [language] on this chip: the ones made for it first, then the multilingual ones. */
    fun forLanguage(language: String) = models.filter { it.kind.transcribes && it.runsOn(chip) }.let { all ->
        all.filter { language in it.languages } + all.filter { SpeechModel.MULTILINGUAL in it.languages && language !in it.languages }
    }

    /** The models for [language] in the catalog: every one, the live ones first. */
    fun catalog(language: String) = forLanguage(language).sortedByDescending { it.runsLive }

    /** The models that can write [language] live: the ready ones first ([ModelChoices]). */
    fun liveModels(language: String) = ModelChoices.candidates(forLanguage(language), ModelJob.Live, ::ready)

    /** The models that can improve [language] (Whisper), the ready ones first, the large ones before the fast. */
    fun refineModels(language: String) = ModelChoices.candidates(forLanguage(language), ModelJob.Improve, ::ready)

    /** The models to get listed under Improve on a language's page: not those already listed under Live. */
    fun improveToGet(language: String) = ModelChoices.improveToGet(forLanguage(language), ::ready)

    fun liveFor(language: String): SpeechModel? = ModelChoices.running(liveModels(language), live[language], ::ready)

    /** [language]'s recommended models on this phone, and whether they are the ones that run ([DefaultModels.setup]). */
    fun setup(language: String): DefaultModels.LanguageSetup = DefaultModels.setup(
        DefaultModels.forLanguage(language, chip) { npu[it.id] is NpuCheck.Fails }, liveFor(language), refineFor(language), ::ready,
    )
    fun refineFor(language: String): SpeechModel? = ModelChoices.running(refineModels(language), refine[language], ::ready)

    val added get() = models.filter { it.fromHub }

    /** The voice-print models: the catalog's (those copied by hand once they are here), then those added from Hugging Face. */
    val voiceModels get() = ModelCatalog.voicePrintModels.filter { !it.manual || status(it) == ModelStatus.Ready } + added.filter { it.kind == ModelKind.SpeakerEmbedding }

    /** The segmentation models for speaker turns, as [voiceModels]. */
    val turnModels get() = ModelCatalog.segmentationModels.filter { !it.manual || status(it) == ModelStatus.Ready } + added.filter { it.kind == ModelKind.SpeakerSegmentation }

    /** Manage models' Speakers: both. */
    val speakerPage get() = voiceModels + turnModels

    /** The voice-print model in use, when it is here. */
    val voice get() = voiceModels.firstOrNull { it.id == voiceModel && ready(it) }

    /** The segmentation model in use, when it is here. */
    val turns get() = turnModels.firstOrNull { it.id == segmentationModel && ready(it) }

    /** What the model's menu offers to free ([ModelChoices.removal]): remove an added model, delete a built-in one's download. */
    fun removal(model: SpeechModel) = ModelChoices.removal(model, status(model) == ModelStatus.Ready)

    /** Chosen for a job (live, improve, voice prints, speaker turns): removing it puts that job back on its default. */
    fun chosen(model: SpeechModel) = model.id in live.values || model.id in refine.values || model.id == voiceModel || model.id == segmentationModel
}

/** The models for speaker labels (live) and the speaker pass (after), every one the speakers page may list. */
val speakerModels get() = ModelCatalog.voicePrintModels + ModelCatalog.segmentationModels

class ModelsActions(
    val back: () -> Unit = {},
    val download: (SpeechModel) -> Unit = {},
    val cancel: (SpeechModel) -> Unit = {},
    val delete: (SpeechModel) -> Unit = {},
    val useLive: (language: String, SpeechModel) -> Unit = { _, _ -> },
    val useRefine: (language: String, SpeechModel) -> Unit = { _, _ -> },
    /** Picks the voice-print model for speaker labels, from the next lecture or speaker pass. */
    val useVoicePrints: (SpeechModel) -> Unit = {},
    /** Sets how readily the voice-print model starts a new speaker, -1 (fewer) to 1 (more); 0 resets. */
    val setSpeakerTuning: (SpeechModel, Float) -> Unit = { _, _ -> },
    /** Picks the segmentation model for live speaker turns, from the next lecture. */
    val useSegmentation: (SpeechModel) -> Unit = {},
    /** Lets an NPU model that failed or was disabled on this phone be tried again. */
    val retryNpu: (SpeechModel) -> Unit = {},
    val setAudioFormat: (AudioFormat) -> Unit = {},
    val setFlacLevel: (Int) -> Unit = {},
    val setAac: (AacSettings) -> Unit = {},
    val setFlac: (com.chencohensolution.transcriptor.settings.FlacFormat) -> Unit = {},
    /** Applies a quality preset (codec and its settings). */
    val setQuality: (com.chencohensolution.transcriptor.settings.QualityPreset) -> Unit = {},
    /** Applies a power preset (when encoding and "After" transcription run). */
    val setPower: (com.chencohensolution.transcriptor.settings.PowerPreset) -> Unit = {},
    val setEncodeAfter: (EncodeAfter) -> Unit = {},
    val setTranscribeWhen: (EncodeAfter) -> Unit = {},
    val setFindSpeakersOnStop: (Boolean) -> Unit = {},
    val setAutoImprove: (Boolean) -> Unit = {},
    val setQuietRecording: (Boolean) -> Unit = {},
    val setStopTwice: (Boolean) -> Unit = {},
    val setAdvancedMode: (Boolean) -> Unit = {},
    /** Opens the phone's notification settings for the app, where its notifications can be turned off altogether. */
    val openNotificationSettings: () -> Unit = {},
    val setProcessorMeters: (ProcessorMeterStyle) -> Unit = {},
    val setLevelMeter: (LevelMeterStyle) -> Unit = {},
    val setMicInput: (com.chencohensolution.transcriptor.recording.MicInput?) -> Unit = {},
    val chooseFolder: () -> Unit = {},
    val openServer: () -> Unit = {},
    /** Shows a language in the catalog, beside the ones in use. */
    val addLanguage: (String) -> Unit = {},
    /** Searches Hugging Face, in a language (null: any), of a kind (null: all), and only this phone's NPU builds. */
    val search: (query: String, language: String?, kind: HubKind?, npuOnly: Boolean) -> Unit = { _, _, _, _ -> },
    /** Adds a model from Hugging Face to the list (pinned to its commit) and starts its download. */
    val install: (HubModel, HubVariant) -> Unit = { _, _ -> },
    /** Takes a model added from Hugging Face off the list, deleting its files. */
    val remove: (SpeechModel) -> Unit = {},
)

/**
 * Settings: the languages in use, each with the model that writes it live and the one that improves its lectures;
 * a language's page picks both (the models to get under them). Then Speaker recognition (a Voice model row, and how
 * readily it starts a new speaker) and Speaker turns (a Turn model row): each row opens the same speaker page, its
 * models to pick and Find more on Hugging Face. Then the switches after a lecture (find the speakers again after
 * Stop, improve automatically), Manage models, the lectures folder and the audio, then Advanced (the processor meters
 * while recording: off, bars, a chip or graphs). **Manage models** is the
 * catalog: per language (Add a language for any other), each model a card with its facts, badges (Live, Improve, NPU,
 * Many languages, From Hugging Face, the NPU check), its download and a menu. **Find on Hugging Face** searches for
 * models the app's engines read, filtered by language, kind and NPU builds for this phone; licenses are marked for
 * commercial use, not, or unknown (asked first); a size is installed pinned to the repo's commit; an NPU model is
 * tried on the NPU once downloaded. No storage bar: the phone's free space isn't this screen's business.
 */
@Composable
fun ModelsScreen(state: ModelsState, actions: ModelsActions, hub: HubSearch) {
    var page by rememberSaveable { mutableStateOf(0) }
    var shown by rememberSaveable { mutableStateOf(state.languages.first()) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    /** The speaker page open ([HubKind.VoicePrints] or [HubKind.SpeakerTurns]), and its Hugging Face search. */
    var speakerJob by rememberSaveable { mutableStateOf<HubKind?>(null) }
    var speakerHub by rememberSaveable { mutableStateOf(false) }
    /** Recording format, or the AAC or FLAC page it opened; null when none is open. */
    var formatPage by rememberSaveable { mutableStateOf<FormatPage?>(null) }
    /** Settings › Advanced is open (the speaker pages and the model library open from it, and return to it). */
    var advanced by rememberSaveable { mutableStateOf(false) }
    val openCatalog = { l: String? -> if (l != null) shown = l; page = 1 }
    when {
        page == 2 -> {
            BackHandler { page = 1 }
            HubPage(state, hub, actions, startLanguage = shown.takeIf { it != SPEAKERS }) { page = 1 }
        }
        page == 1 -> {
            BackHandler { page = 0 }
            CatalogPage(state, actions, shown, { shown = it }, onSearch = { page = 2 }) { page = 0 }
        }
        language != null -> {
            BackHandler { language = null }
            LanguagePage(state, actions, language!!, openCatalog) { language = null }
        }
        speakerJob != null && speakerHub -> {
            BackHandler { speakerHub = false }
            HubPage(state, hub, actions, startLanguage = null, speakerKind = speakerJob) { speakerHub = false }
        }
        formatPage != null -> {
            val shown = formatPage!!
            BackHandler { formatPage = if (shown == FormatPage.Format) null else FormatPage.Format }
            RecordingFormatPages(shown, state, actions, open = { formatPage = it }) {
                formatPage = if (shown == FormatPage.Format) null else FormatPage.Format
            }
        }
        speakerJob != null -> {
            BackHandler { speakerJob = null }
            SpeakerPage(state, actions, speakerJob!!, onFindMore = { speakerHub = true }) { speakerJob = null }
        }
        advanced -> {
            BackHandler { advanced = false }
            AdvancedPage(state, actions, openSpeakers = { speakerJob = it }, openCatalog = { openCatalog(null) }) { advanced = false }
        }
        else -> SettingsPage(state, actions, openLanguage = { language = it }, openAdvanced = { advanced = true }, openFormat = { formatPage = FormatPage.Format })
    }
}

private const val SPEAKERS = "speakers"

/** The presets' view of these settings. */
val ModelsState.recordingChoice get() = com.chencohensolution.transcriptor.settings.RecordingChoice(audioFormat, aac, flac, encodeAfter, transcribeWhen)

/** The version of Qualcomm's runtime the app ships (sherpa-onnx's qnn-libs-2.40.0.251030): NPU builds must match it. */
const val APP_QNN = "2.40"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(state: ModelsState, actions: ModelsActions, openLanguage: (String) -> Unit, openAdvanced: () -> Unit, openFormat: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) LanguagePicker(exclude = state.languages, onDismiss = { picking = false }) { actions.addLanguage(it); picking = false; openLanguage(it) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.notebooks_settings)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_languages))
            state.languages.forEach { l ->
                val live = state.liveFor(l)
                val improve = state.refineFor(l)
                // In plain words: the recommended models run, or one tap gets and picks them. The model names are on the
                // language's own page, for whoever wants another choice.
                val setup = state.setup(l)
                val useRecommended: () -> Unit = {
                    setup.toGet.forEach(actions.download)
                    setup.defaults.live?.let { actions.useLive(l, it) }
                    setup.defaults.improve?.let { actions.useRefine(l, it) }
                    Unit
                }
                val getting = setup.toGet.any { state.status(it) is ModelStatus.Downloading }
                Row(Modifier.fillMaxWidth().clickable { openLanguage(l) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(languageName(l), style = MaterialTheme.typography.bodyLarge)
                        when {
                            getting -> Text(stringResource(R.string.settings_recommended_getting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            setup.state == DefaultModels.Setup.Recommended -> Text(stringResource(R.string.settings_recommended_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            setup.state == DefaultModels.Setup.ToGet -> Text(stringResource(R.string.settings_language_get), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> {
                                Text(
                                    if (live != null) stringResource(R.string.settings_live_is, isolated(live.name)) else stringResource(R.string.settings_live_none),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (improve != null) stringResource(R.string.settings_improve_is, isolated(improve.name)) else stringResource(R.string.settings_improve_none),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (!getting && setup.state != DefaultModels.Setup.Recommended && (setup.defaults.live != null || setup.defaults.improve != null)) {
                            val label = if (setup.toGet.isEmpty()) stringResource(R.string.settings_recommended_use)
                                else stringResource(R.string.settings_recommended_get, modelsSize(setup.bytesToGet))
                            if (setup.state == DefaultModels.Setup.ToGet) FilledTonalButton(onClick = useRecommended, modifier = Modifier.padding(top = 6.dp)) { Text(label) }
                            else TextButton(onClick = useRecommended, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(label) }
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            TextButton(onClick = { picking = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(R.string.models_add_language), Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.settings_after))
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.settings_transcribe_when), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                PageNote(stringResource(R.string.settings_transcribe_when_note))
                listOf(
                    EncodeAfter.Stop to R.string.settings_transcribe_when_stop,
                    EncodeAfter.Charging to R.string.settings_transcribe_when_charging,
                ).forEach { (option, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = state.transcribeWhen == option, role = Role.RadioButton) { actions.setTranscribeWhen(option) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.transcribeWhen == option, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SwitchRow(
                stringResource(R.string.settings_auto_improve),
                stringResource(if (state.autoImprove) R.string.settings_auto_improve_on else R.string.settings_auto_improve_off),
                state.autoImprove, actions.setAutoImprove,
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.settings_storage))
            Text(stringResource(R.string.folder_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
            LecturesFolder(state.folder, actions.chooseFolder)
            NavRow(stringResource(R.string.server_title), state.server, actions.openServer)
            NavRow(stringResource(R.string.rec_format_format), recordingFormatSummary(state), openFormat)
            SwitchRow(
                stringResource(R.string.settings_quiet), stringResource(if (state.quietRecording) R.string.settings_quiet_on else R.string.settings_quiet_off),
                state.quietRecording, actions.setQuietRecording,
            )
            if (state.quietRecording) TextButton(onClick = actions.openNotificationSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.settings_quiet_system))
            }
            SwitchRow(
                stringResource(R.string.settings_stop_twice),
                stringResource(if (state.stopTwice) R.string.settings_stop_twice_on else R.string.settings_stop_twice_off),
                state.stopTwice, actions.setStopTwice,
            )
            HorizontalDivider(Modifier.padding(top = 16.dp))
            NavRow(stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_note), openAdvanced)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Settings › Advanced: what most people never change. The speaker models (voice prints with the "fewer ↔ more
 * speakers" slider, speaker turns), finding the speakers again after Stop, the model library, and the processor
 * meters. The first page keeps the plain choices: the languages, improving on its own, the folder and the audio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedPage(state: ModelsState, actions: ModelsActions, openSpeakers: (HubKind) -> Unit, openCatalog: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.settings_advanced)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SwitchRow(
                stringResource(R.string.settings_advanced_mode),
                stringResource(if (state.advancedMode) R.string.settings_advanced_mode_on else R.string.settings_advanced_mode_off),
                state.advancedMode, actions.setAdvancedMode,
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.settings_recognition))
            PageNote(stringResource(R.string.settings_recognition_note))
            val voice = state.voice
            SummaryRow(stringResource(R.string.settings_voice_model), speakerLine(voice, state)) { openSpeakers(HubKind.VoicePrints) }
            if (voice != null) Box(Modifier.padding(horizontal = 20.dp)) { SpeakerTuning(state.speakerTuning[voice.id] ?: 0f) { actions.setSpeakerTuning(voice, it) } }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.settings_turns))
            PageNote(stringResource(R.string.settings_turns_note))
            SummaryRow(stringResource(R.string.settings_turn_model), speakerLine(state.turns, state)) { openSpeakers(HubKind.SpeakerTurns) }
            SwitchRow(stringResource(R.string.settings_find_speakers), stringResource(R.string.settings_find_speakers_note), state.findSpeakersOnStop, actions.setFindSpeakersOnStop)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.settings_library))
            NavRow(stringResource(R.string.settings_manage), stringResource(R.string.settings_manage_note), openCatalog)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.settings_meters_section))
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.settings_level_meter), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                PageNote(stringResource(R.string.settings_level_meter_note))
                listOf(
                    LevelMeterStyle.Bars to R.string.settings_level_meter_bars,
                    LevelMeterStyle.Lines to R.string.settings_level_meter_lines,
                    LevelMeterStyle.Strip to R.string.settings_level_meter_strip,
                    LevelMeterStyle.Spectrum to R.string.settings_level_meter_spectrum,
                    LevelMeterStyle.Spectrogram to R.string.settings_level_meter_spectrogram,
                ).forEach { (option, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = state.levelMeter == option, role = Role.RadioButton) { actions.setLevelMeter(option) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.levelMeter == option, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.settings_meters), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                PageNote(stringResource(R.string.settings_meters_note))
                listOf(
                    ProcessorMeterStyle.Off to R.string.settings_meters_off,
                    ProcessorMeterStyle.Bars to R.string.settings_meters_bars,
                    ProcessorMeterStyle.Chip to R.string.settings_meters_chip,
                    ProcessorMeterStyle.Graphs to R.string.settings_meters_graphs,
                ).forEach { (option, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = state.processorMeters == option, role = Role.RadioButton) { actions.setProcessorMeters(option) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.processorMeters == option, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A speaker job's page, the same for voice prints ([HubKind.VoicePrints]) and speaker turns ([HubKind.SpeakerTurns]):
 * its note, its models to pick (the ready ones with a radio; picking marks one and stays here), the others with their
 * download, then Find more on Hugging Face, searching that job's models.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerPage(state: ModelsState, actions: ModelsActions, job: HubKind, onFindMore: () -> Unit, onBack: () -> Unit) {
    val voices = job == HubKind.VoicePrints
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(if (voices) R.string.settings_recognition else R.string.settings_turns)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            PageNote(stringResource(if (voices) R.string.settings_recognition_note else R.string.settings_turns_note))
            Choices(
                state, actions, if (voices) state.voiceModels else state.turnModels, if (voices) state.voice else state.turns,
                text = { model, modifier -> SpeakerChoiceText(model, state, modifier) },
                pick = if (voices) actions.useVoicePrints else actions.useSegmentation,
            )
            TextButton(onClick = onFindMore, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                Text(stringResource(R.string.models_find_more), Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A speaker model's line on Settings: its name and facts ("3D-Speaker CAM++ · 7M"), or a red "No model downloaded". */
@Composable
private fun speakerLine(model: SpeechModel?, state: ModelsState): Pair<String, Boolean> =
    if (model == null) stringResource(R.string.settings_no_model) to true
    else (listOf(isolated(speakerName(model, state))) + speakerFacts(model, state)).joinToString(" · ") to false

/**
 * A speaker model's name with where it runs, as the speech models' "· NPU": "DiariZen meeting Base · NPU", "3D-Speaker
 * CAM++ · CPU"; the name alone while it isn't on the phone.
 */
private fun speakerName(model: SpeechModel, state: ModelsState) = when {
    model.id in state.npuVoiceModels -> "${model.name} · NPU"
    state.status(model) == ModelStatus.Ready -> "${model.name} · CPU"
    // Not here yet: "· NPU" when what it downloads includes an NPU build for this phone's chip.
    state.chip == NPU_CHIP && model.files.any { it.name in NPU_SPEAKER_FILES } -> "${model.name} · NPU"
    else -> model.name
}

/** The chip the speaker models' NPU builds are for, and their files ([com.chencohensolution.transcriptor.speech.VoicePrintFiles], window.bin). */
private const val NPU_CHIP = "SM8750"
private val NPU_SPEAKER_FILES = setOf("t150.bin", "t300.bin", "t600.bin", "window.bin")

/** A speaker model's facts: parameters, its download's size (when known), copied by hand, from Hugging Face. */
@Composable
private fun speakerFacts(model: SpeechModel, state: ModelsState) = listOfNotNull(
    isolated(model.parameters).takeIf { model.parameters != "?" },
    modelsSize(model.sizeBytes).takeIf { model.sizeBytes > 0 },
    stringResource(R.string.models_by_hand).takeIf { model.manual },
    stringResource(R.string.models_from_hub).takeIf { model.fromHub },
)

/** A speaker model in a list to pick: its name, its facts, a non-commercial license marked. */
@Composable
private fun SpeakerChoiceText(model: SpeechModel, state: ModelsState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(isolated(speakerName(model, state).replace(" · ", "\u00A0·\u00A0")), style = MaterialTheme.typography.bodyLarge)
        val facts = speakerFacts(model, state)
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if ("-NC" in model.license.uppercase()) Text(
            stringResource(R.string.models_license_nc, model.license), style = MaterialTheme.typography.bodySmall,
            color = LocalTranscriptorColors.current.speakers[3],
        )
    }
}

/** A row with a title, a summary line (red when the flag is set) and an arrow. */
@Composable
private fun SummaryRow(title: String, line: Pair<String, Boolean>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(line.first, style = MaterialTheme.typography.bodySmall, color = if (line.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

/** A language's page: the model that writes it live and the one that improves its lectures, each a list to pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePage(state: ModelsState, actions: ModelsActions, language: String, openCatalog: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(languageName(language)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_live_title))
            PageNote(stringResource(R.string.settings_live_note))
            Choices(state, actions, state.liveModels(language), state.liveFor(language)) { actions.useLive(language, it) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.settings_improve_title))
            PageNote(stringResource(R.string.settings_improve_note))
            Choices(
                state, actions, state.refineModels(language).filter { state.ready(it) } + state.improveToGet(language), state.refineFor(language),
            ) { actions.useRefine(language, it) }
            TextButton(onClick = { openCatalog(language) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_more, languageName(language)))
            }
        }
    }
}

/** The ready models to pick from, then the others with their download (or why one can't be picked). */
@Composable
private fun Choices(
    state: ModelsState, actions: ModelsActions, all: List<SpeechModel>, chosen: SpeechModel?,
    text: @Composable (SpeechModel, Modifier) -> Unit = { model, modifier -> ChoiceText(model, state, modifier) },
    pick: (SpeechModel) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        all.filter { state.ready(it) }.forEach { model ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = chosen == model, role = Role.RadioButton) { pick(model) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == model, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                text(model, Modifier.weight(1f))
                RemovalMenu(model, state, actions)
            }
        }
        all.filter { !state.ready(it) }.forEach { model ->
            val status = state.status(model)
            Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    text(model, Modifier)
                    // Downloaded but not loading on this NPU: why, under its name; it can't be picked.
                    if (status == ModelStatus.Ready) Text(
                        stringResource(if (state.npu[model.id] == NpuCheck.Checking) R.string.models_npu_checking else R.string.models_npu_fails),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.npu[model.id] == NpuCheck.Checking) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    // Why (e.g. not enough memory on this phone), and trying it again on the user's word.
                    (state.npu[model.id] as? NpuCheck.Fails)?.let { fails ->
                        if (fails.reason.isNotBlank()) Text(fails.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { actions.retryNpu(model) }) { Text(stringResource(R.string.models_npu_retry)) }
                    }
                }
                when (status) {
                    is ModelStatus.Downloading -> CircularProgressIndicator(progress = { status.progress }, modifier = Modifier.padding(horizontal = 8.dp).size(24.dp), strokeWidth = 3.dp)
                    ModelStatus.Checking -> CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(24.dp), strokeWidth = 3.dp)
                    ModelStatus.WaitingForWifi -> Text(stringResource(R.string.model_waiting_wifi), style = MaterialTheme.typography.labelSmall)
                    ModelStatus.Ready -> {}
                    is ModelStatus.Failed -> TextButton(onClick = { actions.download(model) }) { Text(stringResource(R.string.models_retry), color = MaterialTheme.colorScheme.error) }
                    // A text button, so a long name keeps the row's width.
                    ModelStatus.Missing -> TextButton(onClick = { actions.download(model) }) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        if (model.sizeBytes > 0) Text(modelsSize(model.sizeBytes), Modifier.padding(start = 4.dp))
                    }
                }
                RemovalMenu(model, state, actions)
            }
        }
    }
}

/** A row's "⋮" with what can be freed: Remove (added from Hugging Face) or Delete download (built in); nothing otherwise. */
@Composable
private fun RemovalMenu(model: SpeechModel, state: ModelsState, actions: ModelsActions) {
    val removal = state.removal(model) ?: return
    var menu by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    if (confirming) RemovalDialog(model, removal, state, actions) { confirming = false }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.models_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(removalLabel(removal)) }, onClick = { menu = false; confirming = true })
        }
    }
}

@Composable
private fun removalLabel(removal: ModelRemoval) =
    stringResource(if (removal == ModelRemoval.Remove) R.string.models_remove else R.string.models_delete_download)

/**
 * Asks before freeing a model: what goes (its files, and its entry for an added model), and that a job it is chosen for
 * goes back to its default. While recording or improving it only says to stop first.
 */
@Composable
private fun RemovalDialog(model: SpeechModel, removal: ModelRemoval, state: ModelsState, actions: ModelsActions, onDismiss: () -> Unit) {
    val remove = removal == ModelRemoval.Remove
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (remove) R.string.models_remove_title else R.string.models_delete_title, isolated(model.name))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) Text(stringResource(R.string.models_removal_busy))
                else {
                    Text(stringResource(if (remove) R.string.models_remove_text else R.string.models_delete_text))
                    if (state.chosen(model)) Text(stringResource(R.string.models_removal_chosen))
                }
            }
        },
        confirmButton = {
            if (!state.busy) TextButton(onClick = { onDismiss(); if (remove) actions.remove(model) else actions.delete(model) }) {
                Text(stringResource(if (remove) R.string.models_remove_confirm else R.string.model_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** A model in a list to pick: its name ("· NPU" kept with it), streaming or many languages, its size once it is here. */
@Composable
private fun ChoiceText(model: SpeechModel, state: ModelsState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(isolated(model.name.replace(" · ", "\u00A0·\u00A0")), style = MaterialTheme.typography.bodyLarge)
        val facts = listOfNotNull(
            stringResource(R.string.settings_streaming).takeIf { model.kind == ModelKind.StreamingTransducer || model.kind == ModelKind.StreamingTransducerNpu },
            stringResource(R.string.models_multilingual).takeIf { SpeechModel.MULTILINGUAL in model.languages },
            modelsSize(model.sizeBytes).takeIf { state.status(model) == ModelStatus.Ready && model.sizeBytes > 0 },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PageNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

@Composable
private fun SwitchRow(title: String, note: String, on: Boolean, set: (Boolean) -> Unit) {
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
private fun NavRow(title: String, note: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** Where finished lectures are kept: in the app only, in a folder outside it, moving there, or the folder lost. */
@Composable
private fun LecturesFolder(folder: Recording.Folder, choose: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (folder) {
            Recording.Folder.NotSet -> {
                Text(stringResource(R.string.folder_in_app), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = choose) { Text(stringResource(R.string.folder_choose)) }
            }
            is Recording.Folder.Ready -> {
                Text(stringResource(R.string.folder_ready, folder.name), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = choose) { Text(stringResource(R.string.folder_change)) }
            }
            is Recording.Folder.Moving -> {
                Text(stringResource(R.string.folder_moving, folder.name, (folder.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { folder.progress }, modifier = Modifier.fillMaxWidth())
            }
            is Recording.Folder.Lost -> {
                Text(stringResource(R.string.folder_lost, folder.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = choose) { Text(stringResource(R.string.folder_choose_again)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogPage(state: ModelsState, actions: ModelsActions, shown: String, show: (String) -> Unit, onSearch: () -> Unit, onBack: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) LanguagePicker(exclude = state.languages, onDismiss = { picking = false }) { actions.addLanguage(it); show(it); picking = false }
    val speakers = shown == SPEAKERS
    val models = if (speakers) state.speakerPage else state.catalog(shown)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.models_title)) },
                actions = { IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.models_find)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.languages + SPEAKERS) { id ->
                        FilterChip(
                            selected = shown == id, onClick = { show(id) },
                            label = { Text(if (id == SPEAKERS) stringResource(R.string.models_speakers) else languageName(id)) },
                        )
                    }
                    item {
                        AssistChip(onClick = { picking = true }, label = { Text(stringResource(R.string.models_add_language)) }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                    }
                }
            }
            item {
                Text(
                    when {
                        speakers -> stringResource(R.string.start_speakers_note)
                        shown in Languages.withOwnModels -> stringResource(R.string.models_language_note)
                        else -> stringResource(R.string.models_multilingual_note, languageName(shown))
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(models, key = { it.id }) { model -> ModelCard(model, if (speakers) null else shown, state, actions) }
            if (!speakers) {
                item {
                    TextButton(onClick = onSearch, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Icon(Icons.Filled.Search, null)
                        Text(stringResource(R.string.models_find_for, languageName(shown)), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(model: SpeechModel, language: String?, state: ModelsState, actions: ModelsActions) {
    val status = state.status(model)
    val npu = state.npu[model.id]
    var menu by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<ModelRemoval?>(null) }
    confirming?.let { RemovalDialog(model, it, state, actions) { confirming = null } }
    val uri = LocalUriHandler.current
    val live = language != null && state.liveFor(language) == model
    val refine = language != null && state.refineFor(language) == model
    val voice = model.kind == ModelKind.SpeakerEmbedding
    val speaks = voice && state.voiceModel == model.id
    val segments = model.kind == ModelKind.SpeakerSegmentation
    val turns = segments && state.segmentationModel == model.id
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(isolated(model.name), style = MaterialTheme.typography.titleMedium)
                Text(modelFacts(model), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (live) Badge(stringResource(R.string.models_badge_live))
                    if (refine) Badge(stringResource(R.string.models_badge_refine))
                    if (speaks) Badge(stringResource(R.string.models_badge_speakers))
                    if (turns) Badge(stringResource(R.string.models_badge_turns))
                    if (model.id in state.npuVoiceModels) Badge(stringResource(R.string.models_npu_long))
                    if ("-NC" in model.license) ColoredBadge(stringResource(R.string.models_license_nc, model.license), ok = false)
                    if (model.manual) Badge(stringResource(R.string.models_by_hand))
                    if (language == null && status == ModelStatus.Ready) Badge(stringResource(R.string.model_ready))
                    if (model.npu) Badge(stringResource(R.string.models_npu_long))
                    when (npu) {
                        NpuCheck.Checking -> Badge(stringResource(R.string.models_npu_checking))
                        NpuCheck.Runs -> ColoredBadge(stringResource(R.string.models_npu_runs), ok = true)
                        is NpuCheck.Fails -> ColoredBadge(stringResource(R.string.models_npu_fails), ok = false)
                        null -> {}
                    }
                    if (!model.runsLive && language != null) Badge(stringResource(R.string.models_after_only))
                    if (SpeechModel.MULTILINGUAL in model.languages && language != null) Badge(stringResource(R.string.models_multilingual))
                    if (model.fromHub) Badge(stringResource(R.string.models_from_hub))
                }
                model.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time) }
                if (npu is NpuCheck.Fails) Text(npu.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Progress(status)
            }
            DownloadControl(model, status, actions)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.models_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    state.removal(model)?.let { removal ->
                        DropdownMenuItem(text = { Text(removalLabel(removal)) }, onClick = { menu = false; confirming = removal })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.models_page)) }, onClick = { menu = false; uri.openUri(model.source) })
                }
            }
        }
    }
}

/** Any language Whisper knows, the phone's first, with a field to find one by name; [exclude] are shown already. */
@Composable
private fun LanguagePicker(exclude: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var find by remember { mutableStateOf("") }
    val all = notebookLanguages().filter { it !in exclude }
    val names = all.associateWith { languageName(it) }
    val shown = all.filter { find.isBlank() || names.getValue(it).contains(find.trim(), ignoreCase = true) || it.startsWith(find.trim().lowercase()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_add_language)) },
        text = {
            Column {
                OutlinedTextField(
                    value = find, onValueChange = { find = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.models_find_language)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown) { tag ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(tag) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(names.getValue(tag), Modifier.weight(1f))
                            if (tag in Languages.withOwnModels) Badge(stringResource(R.string.models_own_models))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** Hugging Face, searched for speech models, filtered by language, kind and this phone's NPU; a result opens its sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubPage(state: ModelsState, hub: HubSearch, actions: ModelsActions, startLanguage: String?, speakerKind: HubKind? = null, onBack: () -> Unit) {
    // A speaker page's search: that job's models only, no language or NPU filter.
    var query by rememberSaveable { mutableStateOf(if (speakerKind != null) "" else hub.query) }
    var language by rememberSaveable { mutableStateOf(startLanguage) }
    var kind by rememberSaveable { mutableStateOf(speakerKind) }
    var npuOnly by rememberSaveable { mutableStateOf(false) }
    var pickingLanguage by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<HubModel?>(null) }
    LaunchedEffect(language, kind, npuOnly) { actions.search(query, language, kind, npuOnly) }
    if (pickingLanguage) LanguagePicker(exclude = emptyList(), onDismiss = { pickingLanguage = false }) { language = it; pickingLanguage = false }
    open?.let { m -> HubSheet(m, state, onDismiss = { open = null }) { v -> actions.install(m, v); open = null } }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(speakerKind?.let { kindLabel(it) } ?: stringResource(R.string.models_find)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.models_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { actions.search(query, language, kind, npuOnly) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (speakerKind == null) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = language != null, onClick = { pickingLanguage = true },
                            label = { Text(language?.let { languageName(it) } ?: stringResource(R.string.models_any_language)) },
                            leadingIcon = { Icon(Icons.Filled.Language, null, Modifier.size(18.dp)) },
                            trailingIcon = if (language != null) ({ Icon(Icons.Filled.Close, stringResource(R.string.models_any_language), Modifier.size(18.dp).clickable { language = null }) }) else null,
                        )
                    }
                    item {
                        // Only on a phone with a Qualcomm NPU the app can use.
                        val chip = state.chip?.takeIf { it.startsWith("SM") }
                        FilterChip(
                            selected = npuOnly, enabled = chip != null, onClick = { npuOnly = !npuOnly },
                            label = { Text(chip?.let { stringResource(R.string.models_npu_filter, it) } ?: stringResource(R.string.models_no_npu)) },
                            leadingIcon = { Icon(Icons.Filled.Memory, null, Modifier.size(18.dp)) },
                        )
                    }
                    item { FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text(stringResource(R.string.models_all_kinds)) }) }
                    items(HubKind.speech) { k ->
                        FilterChip(selected = kind == k, onClick = { kind = if (kind == k) null else k }, label = { Text(kindLabel(k)) })
                    }
                }
                Text(
                    stringResource(if (speakerKind != null) R.string.models_speaker_search_note else R.string.models_search_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (hub.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            hub.error?.let { item { Text(stringResource(R.string.models_search_failed, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) } }
            items(hub.results, key = { it.repo }) { m -> HubRow(m, state) { open = m } }
            // Every other match, greyed, with why the app can't use it: a search shows what Hugging Face found.
            if (!hub.searching && hub.error == null && hub.others.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.models_others, hub.others.size), style = MaterialTheme.typography.labelMedium,
                        color = LocalTranscriptorColors.current.time, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(hub.others, key = { "other:" + it.repo }) { o ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(isolated(o.repo), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text(o.reason, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
                    }
                }
            }
            if (!hub.searching && hub.error == null && hub.results.isEmpty() && hub.others.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.models_none, hub.unusable), style = MaterialTheme.typography.bodySmall,
                        color = LocalTranscriptorColors.current.time, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: HubKind) = stringResource(
    when (kind) {
        HubKind.Whisper -> R.string.models_kind_whisper
        HubKind.Streaming -> R.string.models_kind_streaming
        HubKind.Parakeet -> R.string.models_kind_parakeet
        HubKind.SenseVoice -> R.string.models_kind_sensevoice
        HubKind.Moonshine -> R.string.models_kind_moonshine
        HubKind.Paraformer -> R.string.models_kind_paraformer
        HubKind.VoicePrints -> R.string.models_kind_voice_prints
        HubKind.SpeakerTurns -> R.string.models_kind_speaker_turns
    },
)

@Composable
private fun HubRow(m: HubModel, state: ModelsState, onOpen: () -> Unit) {
    val installed = m.variants.count { v -> state.added.any { it.id == m.toSpeechModel(v).id } }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(isolated(m.name), style = MaterialTheme.typography.titleMedium, color = if (m.kind.runs) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            listOfNotNull(
                isolated(m.author),
                if (m.downloads > 0) stringResource(R.string.models_downloads, grouped(m.downloads)) else null,
                stringResource(R.string.models_likes, grouped(m.likes)),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LicenseBadge(m)
            Badge(kindLabel(m.kind))
            if (!m.kind.runs) Badge(stringResource(R.string.models_not_yet))
            NpuFit(m, state.chip)
            languagesOf(m)?.let { Badge(it) }
            Badge(
                if (m.variants.size == 1) modelsSize(m.variants.single().sizeBytes)
                else stringResource(R.string.models_sizes, m.variants.size, modelsSize(m.variants.minOf { it.sizeBytes })),
            )
            if (installed > 0) Badge(stringResource(R.string.models_installed))
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
}

/** "Hebrew", "English, German, French +22", "Many languages"; null when the card doesn't say. */
@Composable
private fun languagesOf(m: HubModel): String? {
    if (SpeechModel.MULTILINGUAL in m.languages) return stringResource(R.string.models_multilingual)
    if (m.languages.isEmpty()) return null
    val names = m.languages.map { languageName(it) }
    return if (names.size <= 3) names.joinToString() else names.take(3).joinToString() + " +" + (names.size - 3)
}

/** 412,000 (412.000 on a German phone). */
private fun grouped(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

/** The license, colored by what it lets the user do: green for commercial use, amber for not, grey for unknown. */
@Composable
private fun LicenseBadge(m: HubModel) {
    val (color, text) = when (m.use) {
        LicenseUse.Commercial -> LocalTranscriptorColors.current.speakers[1] to stringResource(R.string.models_license_ok, m.license ?: "")
        LicenseUse.NonCommercial -> LocalTranscriptorColors.current.speakers[3] to stringResource(R.string.models_license_nc, m.license ?: "")
        LicenseUse.Unknown -> MaterialTheme.colorScheme.outline to stringResource(R.string.models_license_unknown)
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** An NPU build: for this phone's chip and runtime, another chip, or this chip with another QNN version. */
@Composable
private fun NpuFit(m: HubModel, chip: String?) {
    val n = m.npu ?: return
    val runtime = n.runtime
    when {
        !m.fits(chip) -> ColoredBadge(stringResource(R.string.models_npu_other, n.chip), ok = false)
        runtime != null && runtime != APP_QNN -> ColoredBadge(stringResource(R.string.models_npu_runtime, n.chip, runtime), ok = false)
        else -> ColoredBadge(stringResource(R.string.models_npu_this, n.chip), ok = true)
    }
}

@Composable
private fun ColoredBadge(text: String, ok: Boolean) {
    val color = if (ok) LocalTranscriptorColors.current.speakers[1] else MaterialTheme.colorScheme.error
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * A repo's sheet: what it is, its languages and license (and what that means), the NPU it was built for, the commit it
 * is pinned to, its sizes to pick one. Adding asks first when the license isn't for commercial use or isn't known; a
 * kind the app doesn't run yet, or a build for another chip, says so instead of Add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubSheet(m: HubModel, state: ModelsState, onDismiss: () -> Unit, onInstall: (HubVariant) -> Unit) {
    var chosen by remember { mutableStateOf(m.variants.first()) }
    var confirming by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(if (m.use == LicenseUse.NonCommercial) R.string.models_confirm_nc_title else R.string.models_confirm_unknown_title)) },
            text = { Text(stringResource(if (m.use == LicenseUse.NonCommercial) R.string.models_confirm_nc else R.string.models_confirm_unknown)) },
            confirmButton = { TextButton(onClick = { confirming = false; onInstall(chosen) }) { Text(stringResource(R.string.models_install_anyway)) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.model_cancel)) } },
        )
    }
    // Open all the way, so Add shows below the sizes.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      // The sizes scroll (whisper.cpp has 33); the buttons stay below them.
      Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(isolated(m.repo), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LicenseBadge(m)
                Badge(kindLabel(m.kind))
                NpuFit(m, state.chip)
                languagesOf(m)?.let { Badge(it) }
            }
            m.npu?.let { n ->
                val runtime = n.runtime
                Text(
                    when {
                        !m.fits(state.chip) -> stringResource(R.string.models_npu_note_other, n.chip, state.chip ?: "?")
                        runtime != null && runtime != APP_QNN -> stringResource(R.string.models_npu_note_runtime, runtime, APP_QNN)
                        else -> stringResource(R.string.models_npu_note_this, n.chip, n.runtime ?: "?")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (m.fits(state.chip) && (n.runtime == null || n.runtime == APP_QNN)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(
                    when (m.use) {
                        LicenseUse.Commercial -> R.string.models_license_ok_note
                        LicenseUse.NonCommercial -> R.string.models_license_nc_note
                        LicenseUse.Unknown -> R.string.models_license_unknown_note
                    },
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.models_pinned, isolated(m.commit.take(8))), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
            Text(stringResource(R.string.models_choose_size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
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
                                listOfNotNull(isolated(v.parameters).takeIf { v.parameters != "?" }, modelsSize(v.sizeBytes), if (!v.fitsLive) stringResource(R.string.models_after_only) else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
                            )
                        }
                        if (installed) Badge(stringResource(R.string.models_installed))
                    }
                }
            }
            if (!m.kind.runs) Text(stringResource(R.string.models_not_yet_note, kindLabel(m.kind)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uri.openUri("https://huggingface.co/${m.repo}") }) { Text(stringResource(R.string.models_page)) }
                val already = state.added.any { it.id == m.toSpeechModel(chosen).id }
                Button(
                    onClick = { if (m.use == LicenseUse.Commercial) onInstall(chosen) else confirming = true },
                    enabled = m.kind.runs && !already && m.fits(state.chip), modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            !m.kind.runs -> stringResource(R.string.models_not_yet)
                            !m.fits(state.chip) -> stringResource(R.string.models_not_this_phone)
                            else -> stringResource(R.string.models_install, modelsSize(chosen.sizeBytes))
                        },
                    )
                }
            }
      }
    }
}

/** "874 MB", "1.6 GB", kept left to right in a Hebrew sentence. */
fun modelsSize(bytes: Long) = isolated(if (bytes >= 1e9) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6))

/** A model's facts: parameters, license, download size. */
private fun modelFacts(model: SpeechModel) =
    listOfNotNull(isolated(model.parameters), isolated(model.license), modelsSize(model.sizeBytes).takeIf { model.sizeBytes > 0 }).joinToString(" · ")

/** Runs on this phone's NPU. */
private val SpeechModel.npu get() = kind.onNpu

/** A small label: "NPU", "Live", "From Hugging Face". */
@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * A model's download, as a compact control at the end of its row: Download (with the size), the progress with Cancel,
 * waiting for Wi-Fi, checking, failed with Retry; nothing once it is ready (the row says what it is used for).
 */
@Composable
private fun DownloadControl(model: SpeechModel, status: ModelStatus, actions: ModelsActions) {
    when (status) {
        ModelStatus.Missing -> FilledTonalButton(onClick = { actions.download(model) }) {
            Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
            Text(modelsSize(model.sizeBytes), Modifier.padding(start = 6.dp))
        }
        is ModelStatus.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(progress = { status.progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            IconButton(onClick = { actions.cancel(model) }) { Icon(Icons.Filled.Close, stringResource(R.string.model_cancel)) }
        }
        ModelStatus.WaitingForWifi -> Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.model_waiting_wifi), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { actions.cancel(model) }) { Text(stringResource(R.string.model_cancel)) }
        }
        ModelStatus.Checking -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        is ModelStatus.Failed -> TextButton(onClick = { actions.download(model) }) {
            Text(stringResource(R.string.models_retry), color = MaterialTheme.colorScheme.error)
        }
        ModelStatus.Ready -> {}
    }
}

/** The download's progress under a row, while it runs. */
@Composable
private fun Progress(status: ModelStatus) {
    when (status) {
        is ModelStatus.Downloading -> Column {
            Text(stringResource(R.string.model_downloading, (status.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        ModelStatus.Checking -> Text(stringResource(R.string.model_checking), style = MaterialTheme.typography.bodySmall)
        is ModelStatus.Failed -> Text(stringResource(R.string.model_failed, status.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> {}
    }
}

/**
 * How readily the voice-print model in use starts a new speaker: a slider from "Fewer speakers" to "More speakers",
 * the middle its tuned thresholds, with Reset when moved. Applies from the next recording or speaker pass.
 */
@Composable
private fun SpeakerTuning(value: Float, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(stringResource(R.string.models_speaker_tuning), style = MaterialTheme.typography.labelMedium)
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

/**
 * Settings › Microphone: the phone's own (the default: stereo), or a headset, earbuds or USB microphone connected now.
 * The one picked stays picked while it is away ("not connected"): the phone's own records until it is back.
 */
@Composable
/** The microphone row and its picker; on Settings › Recording format, since the microphone decides what is supported there. */
internal fun MicrophoneRow(state: ModelsState, set: (MicInput?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val picked = state.micInput
    val connected = picked == null || state.micInputs.any { it.key == picked.key }
    NavRow(
        stringResource(R.string.settings_mic),
        when {
            picked == null -> stringResource(R.string.settings_mic_phone)
            connected -> isolated(picked.name)
            else -> stringResource(R.string.settings_mic_away, isolated(picked.name))
        },
    ) { open = true }
    if (!open) return
    val choices = buildList {
        add(null)
        if (picked != null && !connected) add(picked)
        addAll(state.micInputs)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.settings_mic)) },
        text = {
            Column(Modifier.selectableGroup()) {
                choices.forEach { input ->
                    val selected = input?.key == picked?.key
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) { set(input); open = false }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(if (input == null) stringResource(R.string.settings_mic_phone) else isolated(input.name), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    input == null -> stringResource(R.string.settings_mic_phone_note)
                                    state.micInputs.none { it.key == input.key } -> stringResource(R.string.settings_mic_not_connected)
                                    else -> stringResource(micKind(input.kind))
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.micInputs.isEmpty()) Text(
                    stringResource(R.string.settings_mic_none), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_mic_close)) } },
    )
}

/** What kind of microphone [kind] is, and what to expect from it. */
private fun micKind(kind: MicInput.Kind) = when (kind) {
    MicInput.Kind.Bluetooth -> R.string.settings_mic_bluetooth
    MicInput.Kind.BluetoothLe -> R.string.settings_mic_bluetooth_le
    MicInput.Kind.Wired -> R.string.settings_mic_wired
    MicInput.Kind.Usb -> R.string.settings_mic_usb
}
