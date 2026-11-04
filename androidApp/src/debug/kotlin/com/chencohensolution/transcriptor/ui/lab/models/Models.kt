package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.speech.ModelStatus
import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine
import kotlinx.coroutines.delay

// Shared by the Settings › Models options: the state as a controller would produce it, the actions, the lab's fakes,
// and the pieces every option draws the same way (a model's facts, its download control).

/** Settings, with the models per language. */
data class ModelsState(
    /** The languages of the user's notebooks, the most used first. */
    val languages: List<String>,
    /** The phone's chip (`Build.SOC_MODEL`): models compiled for its NPU are offered. */
    val chip: String?,
    val statuses: Map<String, ModelStatus>,
    /** The live model chosen per language; a language without one uses its first ready model. */
    val live: Map<String, String> = emptyMap(),
    /** The refine model chosen per language; likewise. */
    val refine: Map<String, String> = emptyMap(),
    val freeBytes: Long,
    val audioFormat: AudioFormat,
    val afterLecture: Boolean,
    /** Models the user added from Hugging Face (option b2), beside the catalog's. */
    val added: List<SpeechModel> = emptyList(),
) {
    fun status(model: SpeechModel) = statuses[model.id] ?: ModelStatus.Missing
    fun ready(model: SpeechModel) = status(model) == ModelStatus.Ready

    /** The models that can transcribe [language] live, the ones made for it first. */
    fun liveModels(language: String) = (ModelCatalog.forLanguage(language, chip) + addedFor(language)).filter { it.fitsLive }

    /** The models that can refine [language]: any that transcribes it, the large ones first. */
    fun refineModels(language: String) = (ModelCatalog.forLanguage(language, chip) + addedFor(language)).sortedBy { it.fitsLive }

    /** The added models that know [language] (multilingual ones included). */
    fun addedFor(language: String) = added.filter { language in it.languages || SpeechModel.MULTILINGUAL in it.languages }

    fun liveFor(language: String): SpeechModel? = pick(liveModels(language), live[language])
    fun refineFor(language: String): SpeechModel? = pick(refineModels(language), refine[language])

    /** What the app suggests for [language] on this phone: the first live model, the first large one, the speakers'. */
    fun recommended(language: String): List<SpeechModel> =
        listOfNotNull(liveModels(language).firstOrNull(), refineModels(language).firstOrNull { !it.fitsLive }) + speakerModels

    val usedBytes get() = (ModelCatalog.all + speakerModels + added).filter { ready(it) }.sumOf { it.sizeBytes }

    private fun pick(models: List<SpeechModel>, chosen: String?) =
        models.firstOrNull { it.id == chosen && ready(it) } ?: models.firstOrNull { ready(it) }
}

/** The models for speaker labels (live) and the speaker pass (after): every language's. */
val speakerModels = listOf(ModelCatalog.voicePrints, ModelCatalog.speakerSegmentation)

class ModelsActions(
    val back: () -> Unit = {},
    val download: (SpeechModel) -> Unit = {},
    val cancel: (SpeechModel) -> Unit = {},
    val delete: (SpeechModel) -> Unit = {},
    val useLive: (language: String, SpeechModel) -> Unit = { _, _ -> },
    val useRefine: (language: String, SpeechModel) -> Unit = { _, _ -> },
    val setAudioFormat: (AudioFormat) -> Unit = {},
    val setAfterLecture: (Boolean) -> Unit = {},
)

/**
 * Fakes for the lab: downloads (a model's size at 60 MB/s, then two seconds of checking), the free space, the
 * languages (the lab notebooks'). To start with: Hebrew's turbo and the speaker models are ready, Whisper turbo waits
 * for Wi-Fi and Whisper large-v3 failed. The chip is the phone's.
 */
@Composable
internal fun rememberModelsLab(added: List<SpeechModel> = emptyList()): Pair<ModelsState, ModelsActions> {
    val known by rememberUpdatedState(ModelCatalog.all + speakerModels + added)
    var statuses by remember {
        mutableStateOf(
            mapOf(
                ModelCatalog.ivritTurbo.id to ModelStatus.Ready,
                ModelCatalog.voicePrints.id to ModelStatus.Ready,
                ModelCatalog.speakerSegmentation.id to ModelStatus.Ready,
                ModelCatalog.whisperTurbo.id to ModelStatus.WaitingForWifi,
                ModelCatalog.whisperLargeV3.id to ModelStatus.Failed("download error 1008"),
            ),
        )
    }
    var live by remember { mutableStateOf(emptyMap<String, String>()) }
    var refine by remember { mutableStateOf(emptyMap<String, String>()) }
    var format by remember { mutableStateOf(AudioFormat.WavThenFlac) }
    var after by remember { mutableStateOf(true) }
    val downloading = statuses.filterValues { it is ModelStatus.Downloading || it == ModelStatus.Checking }.keys
    LaunchedEffect(downloading.isNotEmpty()) {
        var checking = emptyMap<String, Int>()
        while (statuses.values.any { it is ModelStatus.Downloading || it == ModelStatus.Checking }) {
            delay(200)
            statuses = statuses.mapValues { (id, s) ->
                val model = known.firstOrNull { it.id == id } ?: return@mapValues s
                when (s) {
                    is ModelStatus.Downloading -> {
                        val p = s.progress + 12_000_000f / model.sizeBytes
                        if (p >= 1f) ModelStatus.Checking.also { checking = checking + (id to 0) } else ModelStatus.Downloading(p)
                    }
                    ModelStatus.Checking -> {
                        val ticks = (checking[id] ?: 0) + 1
                        checking = checking + (id to ticks)
                        if (ticks >= 10) ModelStatus.Ready else s
                    }
                    else -> s
                }
            }
        }
    }
    val state = ModelsState(
        languages = listOf("he", "en"), chip = SherpaNpuWhisperEngine.chip, statuses = statuses, live = live, refine = refine,
        freeBytes = 41_300_000_000 - (ModelCatalog.all + speakerModels).filter { statuses[it.id] == ModelStatus.Ready }.sumOf { it.sizeBytes },
        audioFormat = format, afterLecture = after,
    )
    val actions = ModelsActions(
        download = { statuses = statuses + (it.id to ModelStatus.Downloading(0f)) },
        cancel = { statuses = statuses - it.id },
        delete = { statuses = statuses - it.id },
        useLive = { language, model -> live = live + (language to model.id) },
        useRefine = { language, model -> refine = refine + (language to model.id) },
        setAudioFormat = { format = it },
        setAfterLecture = { after = it },
    )
    return state to actions
}

/** "874 MB", "1.6 GB", kept left to right in a Hebrew sentence. */
internal fun modelsSize(bytes: Long) = isolated(if (bytes >= 1e9) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6))

/** A model's facts: parameters, license, download size. */
internal fun modelFacts(model: SpeechModel) = listOf(isolated(model.parameters), isolated(model.license), modelsSize(model.sizeBytes)).joinToString(" · ")

/** Its name, kept in its own direction in a right-to-left screen ("3D-Speaker CAM++", not "++3D-Speaker CAM"). */
internal val SpeechModel.label get() = isolated(name)

/** Runs on this phone's NPU. */
internal val SpeechModel.npu get() = kind == ModelKind.WhisperNpu || kind == ModelKind.StreamingTransducerNpu

/** A small label: "NPU", "Live", "In use". */
@Composable
internal fun ModelsBadge(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * A model's download, as a compact control at the end of its row: Download (with the size), the progress with Cancel,
 * waiting for Wi-Fi, checking, failed with Retry; nothing once it is ready (the row says what it is used for).
 */
@Composable
internal fun ModelsDownloadControl(model: SpeechModel, status: ModelStatus, actions: ModelsActions) {
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
internal fun ModelsProgress(status: ModelStatus) {
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
