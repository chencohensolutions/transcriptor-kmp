package com.chencohensolution.transcriptor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.transcriptor.models.HubSearch
import com.chencohensolution.transcriptor.models.InstalledModel
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.speech.ModelStatus
import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine
import com.chencohensolution.transcriptor.ui.ModelsActions
import com.chencohensolution.transcriptor.ui.ModelsScreen
import com.chencohensolution.transcriptor.ui.ModelsState
import com.chencohensolution.transcriptor.ui.NpuCheck
import com.chencohensolution.transcriptor.ui.speakerModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings (its languages' pages) and Manage models, fed from [Recording]: the models' downloads (read every second while it is open),
 * the settings, the languages in use, the Hugging Face search, models added and removed, and each NPU model's
 * one-time check once it is downloaded.
 */
@Composable
fun Settings(onBack: () -> Unit, chooseFolder: () -> Unit) {
    val scope = rememberCoroutineScope()
    val library by Recording.library.library.collectAsStateWithLifecycle()
    val folder by Recording.folder.collectAsStateWithLifecycle()
    var changed by remember { mutableStateOf(0) }
    var format by remember { mutableStateOf(Recording.settings.audioFormat) }
    var speakers by remember { mutableStateOf(Recording.settings.findSpeakersOnStop) }
    var auto by remember { mutableStateOf(Recording.settings.autoImprove) }
    var added by remember { mutableStateOf(Recording.settings.modelLanguages) }
    var hub by remember { mutableStateOf(HubSearch()) }
    var checking by remember { mutableStateOf(emptySet<String>()) }
    val chip = SherpaNpuWhisperEngine.chip
    val models = remember(changed) { Recording.modelLibrary.all }
    val statuses by produceState(emptyMap<String, ModelStatus>(), models) {
        while (true) {
            value = withContext(Dispatchers.IO) { (models + speakerModels).associate { it.id to Recording.models.status(it) } }
            delay(1_000)
        }
    }
    val npu = remember(statuses, checking, changed) {
        models.filter { it.kind == ModelKind.WhisperNpu || it.kind == ModelKind.StreamingTransducerNpu }.mapNotNull { m ->
            when {
                m.id in checking -> m.id to NpuCheck.Checking
                else -> Recording.modelLibrary.npu(m.id)?.let { m.id to if (it.runs) NpuCheck.Runs else NpuCheck.Fails(it.reason ?: "") }
            }
        }.toMap()
    }
    // An NPU model is tried on the NPU once, after its download.
    LaunchedEffect(statuses) {
        for (m in models.filter { (it.kind == ModelKind.WhisperNpu || it.kind == ModelKind.StreamingTransducerNpu) && it.runsOn(chip) }) {
            if (statuses[m.id] != ModelStatus.Ready || m.id in checking || Recording.modelLibrary.npu(m.id) != null) continue
            checking = checking + m.id
            scope.launch {
                withContext(Dispatchers.IO) { Recording.checkNpu(m) }
                checking = checking - m.id
                changed++
            }
        }
    }
    val languages = (library.notebooks.map { it.language } + library.unfiledLanguage + Recording.phoneLanguage() + added).distinct()
    val state = ModelsState(
        languages = languages, chip = chip, models = models, statuses = statuses,
        live = languages.mapNotNull { l -> Recording.settings.liveModel(l)?.let { l to it } }.toMap(),
        refine = languages.mapNotNull { l -> Recording.settings.refineModel(l)?.let { l to it } }.toMap(),
        npu = npu, audioFormat = format, findSpeakersOnStop = speakers, autoImprove = auto, folder = folder,
        voiceModel = remember(statuses, changed) { Recording.voiceModel?.id },
        segmentationModel = remember(statuses, changed) { Recording.segmentationModel?.id },
        speakerTuning = remember(changed) { speakerModels.associate { it.id to Recording.settings.speakerTuning(it.id) } },
        npuVoiceModels = remember(statuses) {
            speakerModels.filter { it.kind == ModelKind.SpeakerEmbedding && statuses[it.id] == ModelStatus.Ready && Recording.voicePrintFiles(it).npu.isNotEmpty() }
                .map { it.id }.toSet()
        },
    )
    val io = { work: () -> Unit -> scope.launch(Dispatchers.IO) { work(); changed++ }; Unit }
    ModelsScreen(
        state,
        ModelsActions(
            back = onBack,
            download = { m -> io { Recording.models.download(m) } },
            cancel = { m -> io { Recording.models.cancel(m) } },
            delete = { m -> io { Recording.models.delete(m) } },
            useLive = { l, m -> io { Recording.settings.setLiveModel(l, m.id) } },
            useRefine = { l, m -> io { Recording.settings.setRefineModel(l, m.id) } },
            useVoicePrints = { m -> io { Recording.settings.voiceModel = m.id } },
            setSpeakerTuning = { m, more -> io { Recording.settings.setSpeakerTuning(m.id, more) } },
            useSegmentation = { m -> io { Recording.settings.segmentationModel = m.id } },
            setAudioFormat = { format = it; io { Recording.settings.audioFormat = it } },
            setFindSpeakersOnStop = { speakers = it; io { Recording.settings.findSpeakersOnStop = it } },
            setAutoImprove = { auto = it; io { Recording.settings.autoImprove = it } },
            chooseFolder = chooseFolder,
            addLanguage = { l -> added = (added + l).distinct(); io { Recording.settings.modelLanguages = added } },
            search = { query, language, kind, npuOnly ->
                hub = HubSearch(query, language, kind, npuOnly, searching = true, results = hub.results)
                scope.launch {
                    hub = runCatching { Recording.hub.search(query, language, kind, npuOnly, chip) }.fold(
                        { (found, unusable) -> hub.copy(searching = false, results = found, unusable = unusable, error = null) },
                        { hub.copy(searching = false, error = it.message ?: it.toString()) },
                    )
                }
            },
            install = { m, v ->
                val model = m.toSpeechModel(v)
                io {
                    Recording.installedModels.add(InstalledModel(model, m.repo, m.use))
                    Recording.models.download(model)
                }
            },
            remove = { m -> io { Recording.models.delete(m); Recording.installedModels.remove(m.id) } },
        ),
        hub,
    )
}
