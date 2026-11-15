package com.chencohensolution.transcriptor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.models.DefaultModels
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.speech.ModelStatus
import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine
import com.chencohensolution.transcriptor.ui.FirstRunActions
import com.chencohensolution.transcriptor.ui.FirstRunJob
import com.chencohensolution.transcriptor.ui.FirstRunModel
import com.chencohensolution.transcriptor.ui.FirstRunScreen
import com.chencohensolution.transcriptor.ui.FirstRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First run is offered: not answered yet, and nothing that transcribes is on the phone (files: not on the main thread). */
fun offerFirstRun(): Boolean = DefaultModels.offerFirstRun(
    done = Recording.settings.firstRunDone,
    anyTranscriberReady = Recording.modelLibrary.all.any { it.kind.transcribes && Recording.models.isReady(it) },
)

/**
 * First run, fed from [Recording]: the phone language's defaults ([DefaultModels]) and their downloads, read every
 * second. Download makes them the language's choices and gets them through [Recording.models], as Settings › Models
 * does; an NPU build is tried once it is here, and if it doesn't load the CPU build is got in its place.
 */
@Composable
fun FirstRun(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val language = remember { Recording.phoneLanguage() }
    val chip = SherpaNpuWhisperEngine.chip
    var started by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(0) }
    val defaults = remember(changed) {
        DefaultModels.forLanguage(language, chip, fails = { Recording.modelLibrary.npu(it.id)?.runs == false })
    }
    val models = listOfNotNull(
        defaults.live?.let { it to FirstRunJob.Live },
        defaults.improve?.let { it to FirstRunJob.Improve },
        defaults.voicePrints to FirstRunJob.Voices,
        defaults.segmentation to FirstRunJob.Turns,
    ).distinctBy { it.first.id }
    val statuses by produceState(emptyMap<String, ModelStatus>(), models) {
        while (true) {
            value = withContext(Dispatchers.IO) { models.associate { (m, _) -> m.id to Recording.models.status(m) } }
            delay(1_000)
        }
    }
    val io = { work: () -> Unit -> scope.launch(Dispatchers.IO) { work(); changed++ }; Unit }
    // One write after another on the same thread: settings.json is read, changed and written whole each time.
    val get = { list: List<SpeechModel>, answered: Boolean ->
        io {
            if (answered) Recording.settings.firstRunDone = true
            Recording.settings.useDefaults(language, defaults)
            list.filter { !Recording.models.isReady(it) }.sortedBy { it.sizeBytes }.forEach { Recording.models.download(it) }
        }
    }
    // An NPU build is tried on the NPU once it is here; one that doesn't load gives way to the CPU build (below).
    var checking by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(statuses) {
        for ((m, _) in models) {
            if (!m.loadsOnNpu || statuses[m.id] != ModelStatus.Ready || m.id in checking || Recording.modelLibrary.npu(m.id) != null) continue
            checking = checking + m.id
            launch {
                withContext(Dispatchers.IO) { Recording.checkNpu(m) }
                checking = checking - m.id
                changed++
            }
        }
    }
    // After a failed NPU check the defaults change: the fallback becomes the language's choice and is got.
    LaunchedEffect(defaults) {
        if (!started) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            defaults.live?.let { if (Recording.modelLibrary.npu(Recording.settings.liveModel(language) ?: "")?.runs == false) Recording.settings.setLiveModel(language, it.id) }
            defaults.improve?.let { if (Recording.modelLibrary.npu(Recording.settings.refineModel(language) ?: "")?.runs == false) Recording.settings.setRefineModel(language, it.id) }
        }
        get(models.map { it.first }, false)
    }
    FirstRunScreen(
        FirstRunState(
            language = language,
            models = models.map { (m, job) ->
                val status = statuses[m.id] ?: ModelStatus.Missing
                // Downloaded but still being tried on the NPU: not ready yet.
                FirstRunModel(m, job, if (status == ModelStatus.Ready && m.id in checking) ModelStatus.Checking else status)
            },
            started = started,
        ),
        FirstRunActions(
            download = {
                started = true
                get(models.map { it.first }, true)
            },
            later = { io { Recording.settings.firstRunDone = true }; onDone() },
            retry = { m -> io { Recording.models.download(m) } },
            done = onDone,
        ),
    )
}
