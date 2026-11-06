package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.models.HubSearch
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.speech.ModelStatus
import kotlinx.coroutines.delay

// Fakes: the phone is a Snapdragon 8 Elite (SM8750) whatever it is, so the NPU models show and pass the NPU check;
// downloads (60 MB/s, then two seconds of checking); the languages (Hebrew and English). To start with, Hebrew has
// turbo (CPU and NPU) and large-v3 (NPU) downloaded, English only Whisper large-v3 (to improve, not live). The two
// switches are in memory: the app has one, "Improve after the lecture", and nothing improves on its own yet. Manage
// models opens today's Settings › Models screen (still with its Use for live / Use to refine items, which this option
// takes away).
internal val modelsB = LabOption("b", "A page per language", "Settings lists the languages; each page picks its live and improve models; two switches") {
    ModelsLabB()
}

@Composable
private fun ModelsLabB() {
    var statuses by remember {
        mutableStateOf(
            listOf(ModelCatalog.ivritTurboNpu, ModelCatalog.ivritTurbo, ModelCatalog.ivritLargeV3Npu, ModelCatalog.whisperLargeV3, ModelCatalog.voicePrints, ModelCatalog.speakerSegmentation)
                .associate { it.id to (ModelStatus.Ready as ModelStatus) },
        )
    }
    var live by remember { mutableStateOf(emptyMap<String, String>()) }
    var refine by remember { mutableStateOf(emptyMap<String, String>()) }
    var format by remember { mutableStateOf(AudioFormat.WavThenFlac) }
    var speakers by remember { mutableStateOf(true) }
    var auto by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    val known = ModelCatalog.all + speakerModels
    LaunchedEffect(statuses.values.any { it is ModelStatus.Downloading || it == ModelStatus.Checking }) {
        val ticks = mutableMapOf<String, Int>()
        while (statuses.values.any { it is ModelStatus.Downloading || it == ModelStatus.Checking }) {
            delay(200)
            statuses = statuses.mapValues { (id, s) ->
                val model = known.firstOrNull { it.id == id } ?: return@mapValues s
                when (s) {
                    is ModelStatus.Downloading -> (s.progress + 12_000_000f / model.sizeBytes).let { if (it >= 1f) ModelStatus.Checking else ModelStatus.Downloading(it) }
                    ModelStatus.Checking -> { ticks[id] = (ticks[id] ?: 0) + 1; if (ticks.getValue(id) >= 10) ModelStatus.Ready else s }
                    else -> s
                }
            }
        }
    }
    val chip = "SM8750"
    val models = ModelsState(
        languages = listOf("he", "en"), chip = chip, models = ModelCatalog.all, statuses = statuses, live = live, refine = refine,
        npu = ModelCatalog.all.filter { it.chips.isNotEmpty() && statuses[it.id] == ModelStatus.Ready }.associate { it.id to NpuCheck.Runs },
        audioFormat = format, afterLecture = speakers,
    )
    val modelsActions = ModelsActions(
        back = { library = false },
        download = { m -> statuses = statuses + (m.id to ModelStatus.Downloading(0f)) },
        cancel = { statuses = statuses - it.id },
        delete = { statuses = statuses - it.id },
        useLive = { l, m -> live = live + (l to m.id) },
        useRefine = { l, m -> refine = refine + (l to m.id) },
        setAudioFormat = { format = it },
    )
    if (library) {
        BackHandler { library = false }
        ModelsScreen(models, modelsActions, HubSearch())
        return
    }
    ModelsScreenB(
        ModelsStateB(models, findSpeakersOnStop = speakers, autoImprove = auto),
        ModelsActionsB(modelsActions, setFindSpeakersOnStop = { speakers = it }, setAutoImprove = { auto = it }, openLibrary = { library = true }),
    )
}
