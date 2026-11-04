package com.chencohensolution.transcriptor

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.speech.ModelStatus
import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.lifecycle.lifecycleScope
import com.chencohensolution.transcriptor.review.LectureReview
import com.chencohensolution.transcriptor.review.ModelChoice
import com.chencohensolution.transcriptor.review.PassState
import com.chencohensolution.transcriptor.ui.LectureScreen
import com.chencohensolution.transcriptor.ui.ReviewActions
import com.chencohensolution.transcriptor.ui.ReviewScreen
import kotlinx.coroutines.launch
import com.chencohensolution.transcriptor.ui.LiveLectureActions
import com.chencohensolution.transcriptor.ui.TranscriptorTheme
import com.chencohensolution.transcriptor.ui.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * For now: a start screen (the audio format, the models, Start lecture, the lectures), the lecture while recording, and
 * a recorded lecture opened (after Stop, or from the list).
 * The start screen gives way to the Notebooks screen once its design is chosen in the UI lab, and the audio format
 * moves to Settings.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TranscriptorTheme {
                val live by Recording.session.state.collectAsStateWithLifecycle()
                val opened by Recording.review.state.collectAsStateWithLifecycle()
                val last by Recording.lastLecture.collectAsStateWithLifecycle()
                var openAfterStop by remember { mutableStateOf(false) }
                // After Stop, the lecture just recorded opens once its last lines are in.
                LaunchedEffect(last, live) {
                    val lecture = last
                    if (openAfterStop && live == null && lecture != null) {
                        openAfterStop = false
                        openLecture(lecture)
                    }
                }
                ReviewPasses()
                val state = live
                val review = opened
                when {
                    state != null -> LectureScreen(
                        state,
                        LiveLectureActions(
                            back = { moveTaskToBack(true) },
                            pauseOrResume = Recording.session::pauseOrResume,
                            stop = { openAfterStop = true; RecordingService.stop(this) },
                            addNote = Recording.session::addNote,
                            renameSpeaker = Recording.session::renameSpeaker,
                        ),
                    )
                    review != null -> {
                        BackHandler { Recording.review.close() }
                        val r = Recording.review
                        ReviewScreen(
                            review,
                            ReviewActions(
                                back = r::close, playPause = r::playPause, seek = r::seek, playLine = r::playLine,
                                cycleSpeed = r::cycleSpeed, editLine = r::editLine, setLineSpeaker = r::setLineSpeaker,
                                renameSpeaker = { id, name -> lifecycleScope.launch(Dispatchers.IO) { r.renameSpeaker(id, name) } },
                                findSpeakers = { RecordingService.findSpeakers(this, review.lecture) },
                                refine = { model -> RecordingService.refine(this, review.lecture, model) },
                                switchVersion = r::switchTo,
                                addNote = r::addNote, editNote = r::editNote, deleteNote = r::deleteNote,
                            ),
                        )
                    }
                    else -> StartScreen(onStart = ::startLecture, onOpen = ::openLecture)
                }
            }
        }
    }

    private fun openLecture(lecture: Lecture) {
        Recording.review.open(lecture.notebookId, lecture.id, getString(R.string.start_default_notebook))
    }

    private fun startLecture() {
        val title = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        RecordingService.start(this, DEFAULT_NOTEBOOK.copy(title = getString(R.string.start_default_notebook)), title)
    }

    private companion object {
        /** Until notebooks exist: one notebook, in Hebrew. */
        val DEFAULT_NOTEBOOK = Notebook("default", "", "he", emptyList())
    }
}

/** Feeds the open lecture's passes from the recording service: running, done, or unavailable without their models. */
@Composable
private fun ReviewPasses() {
    val finding by Recording.findingSpeakers.collectAsStateWithLifecycle()
    val refining by Recording.refining.collectAsStateWithLifecycle()
    val opened by Recording.review.state.collectAsStateWithLifecycle()
    val lecture = opened?.lecture
    LaunchedEffect(finding, refining, lecture?.speakerPass, lecture?.refinedWith, lecture?.id) {
        if (lecture == null) { Recording.review.passes.value = LectureReview.Passes(); return@LaunchedEffect }
        val speakers = finding
        val refine = refining?.takeIf { it.first == lecture.id }?.second
        val models = Recording.refineModels(lecture.language)
        Recording.review.passes.value = LectureReview.Passes(
            speakers = when {
                !Recording.canFindSpeakers -> null
                speakers != null -> PassState.Running(speakers)
                lecture.speakerPass -> PassState.Done
                else -> PassState.NotRun
            },
            refine = when {
                models.isEmpty() || !Recording.settings.afterLecture -> null
                refine != null -> PassState.Running(refine)
                lecture.refinedWith != null -> PassState.Done
                else -> PassState.NotRun
            },
            refineModels = models.map { ModelChoice(it.id, it.name) },
        )
    }
}

@Composable
private fun StartScreen(onStart: () -> Unit, onOpen: (Lecture) -> Unit) {
    var format by remember { mutableStateOf(Recording.settings.audioFormat) }
    val last by Recording.lastLecture.collectAsStateWithLifecycle()
    val compressing by Recording.compressing.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        // The latest lecture, when the app opens.
        if (Recording.lastLecture.value == null) {
            Recording.lastLecture.value = withContext(Dispatchers.IO) { Recording.store.all().maxByOrNull { it.startedAtMillis } }
        }
        val pending = withContext(Dispatchers.IO) { Recording.compression.pending() }
        if (pending.isNotEmpty() && compressing == null) RecordingService.compressPending(context)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) onStart()
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.start_format), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                listOf(
                    AudioFormat.Wav to R.string.format_wav,
                    AudioFormat.WavThenFlac to R.string.format_wav_then_flac,
                    AudioFormat.FlacLive to R.string.format_flac_live,
                ).forEach { (option, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = format == option, role = Role.RadioButton,
                            onClick = { format = option; Recording.settings.audioFormat = option },
                        ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == option, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            LiveModels("he")
            AfterLecture()
            SpeakerModel()
            Button(
                onClick = { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.start_lecture)) }
            last?.let { LastLecture(it, compressing) }
            Lectures(last, onOpen)
            Text(stringResource(R.string.main_coming_soon), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The live models for [language], each with its download; the first one ready is the one used. */
@Composable
private fun LiveModels(language: String) {
    val models = ModelCatalog.forLanguage(language, SherpaNpuWhisperEngine.chip).filter { it.fitsLive }
    var refresh by remember { mutableStateOf(0) }
    val statuses by produceState(emptyMap<String, ModelStatus>(), refresh) {
        while (true) {
            value = models.associate { it.id to Recording.models.status(it) }
            delay(1_000)
        }
    }
    // Read again when a download finishes or a model is chosen.
    val inUse = remember(statuses, refresh) { liveModelFor(language) }
    Text(stringResource(R.string.start_live_models), style = MaterialTheme.typography.titleSmall)
    models.forEach { model ->
        ModelRow(
            model, statuses[model.id], model == inUse,
            choose = { Recording.settings.setLiveModel(language, model.id); refresh++ },
        ) { refresh++ }
    }
}

/** Whether the passes after the lecture run (the speaker pass, the refine pass); the refine models when they do. */
@Composable
private fun AfterLecture() {
    var on by remember { mutableStateOf(Recording.settings.afterLecture) }
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = { on = it; Recording.settings.afterLecture = it }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.start_after_lecture), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.start_after_lecture_note), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
    if (on) RefineModels("he")
}

/** The refine models for [language] (the refine pass after the lecture); the first one ready is the one used. */
@Composable
private fun RefineModels(language: String) {
    val models = ModelCatalog.forLanguage(language, SherpaNpuWhisperEngine.chip).filter { !it.fitsLive && it.kind == ModelKind.Whisper }
    var refresh by remember { mutableStateOf(0) }
    val statuses by produceState(emptyMap<String, ModelStatus>(), refresh) {
        while (true) {
            value = models.associate { it.id to Recording.models.status(it) }
            delay(1_000)
        }
    }
    val inUse = remember(statuses, refresh) { Recording.refineModels(language).firstOrNull { !it.fitsLive } }
    Text(stringResource(R.string.start_refine_models), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.start_refine_note), style = MaterialTheme.typography.bodySmall)
    models.forEach { model -> ModelRow(model, statuses[model.id], model == inUse, choose = {}) { refresh++ } }
}

/** The voice-print model for speaker labels: labels are on once it is downloaded. */
@Composable
private fun SpeakerModel() {
    val models = listOf(ModelCatalog.voicePrints, ModelCatalog.speakerSegmentation)
    var refresh by remember { mutableStateOf(0) }
    val statuses by produceState(emptyMap<String, ModelStatus>(), refresh) {
        while (true) {
            value = models.associate { it.id to Recording.models.status(it) }
            delay(1_000)
        }
    }
    Text(stringResource(R.string.start_speakers), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.start_speakers_note), style = MaterialTheme.typography.bodySmall)
    models.forEach { model ->
        ModelRow(model, statuses[model.id], inUse = statuses[model.id] == ModelStatus.Ready, choose = {}) { refresh++ }
    }
}

@Composable
private fun ModelRow(model: SpeechModel, status: ModelStatus?, inUse: Boolean, choose: () -> Unit, changed: () -> Unit) {
    var startError by remember { mutableStateOf<String?>(null) }
    val download = { startError = Recording.models.download(model); changed() }
    Column(Modifier.fillMaxWidth()) {
        Text(model.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${model.parameters} · ${model.license}" + when {
                !inUse || !model.kind.transcribes -> ""
                model.fitsLive -> " · " + stringResource(R.string.model_in_use)
                else -> " · " + stringResource(R.string.model_in_use_refine)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        model.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                null -> {}
                ModelStatus.Missing -> TextButton(onClick = download) {
                    Text(stringResource(R.string.model_download, sizeText(model.sizeBytes)))
                }
                is ModelStatus.Failed -> {
                    Text(stringResource(R.string.model_failed, status.reason), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = download) {
                        Text(stringResource(R.string.model_download, sizeText(model.sizeBytes)))
                    }
                }
                is ModelStatus.Downloading, ModelStatus.WaitingForWifi -> {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (status is ModelStatus.Downloading) stringResource(R.string.model_downloading, (status.progress * 100).toInt())
                            else stringResource(R.string.model_waiting_wifi),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (status is ModelStatus.Downloading) LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { Recording.models.cancel(model); changed() }) { Text(stringResource(R.string.model_cancel)) }
                }
                ModelStatus.Checking -> Text(stringResource(R.string.model_checking), style = MaterialTheme.typography.bodySmall)
                ModelStatus.Ready -> {
                    startError = null
                    Text(stringResource(R.string.model_ready), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (!inUse) TextButton(onClick = choose) { Text(stringResource(R.string.model_use_live)) }
                    TextButton(onClick = { Recording.models.delete(model); changed() }) { Text(stringResource(R.string.model_delete)) }
                }
            }
        }
    }
}

private fun sizeText(bytes: Long) = if (bytes >= 1e9) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

/** The recorded lectures, newest first; tap one to open it. Until the Notebooks screen is designed. */
@Composable
private fun Lectures(last: Lecture?, onOpen: (Lecture) -> Unit) {
    val lectures by produceState(emptyList<Lecture>(), last) {
        value = withContext(Dispatchers.IO) { Recording.store.all().sortedByDescending { it.startedAtMillis } }
    }
    if (lectures.isEmpty()) return
    Text(stringResource(R.string.start_lectures), style = MaterialTheme.typography.titleSmall)
    Column {
        lectures.forEach { lecture ->
            val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lecture.startedAtMillis))
            Column(Modifier.fillMaxWidth().clickable { onOpen(lecture) }.padding(vertical = 8.dp)) {
                Text(lecture.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$started · ${formatClock(lecture.durationMillis)} · " + stringResource(R.string.speaker_lines, lecture.segments.size) +
                        if (lecture.speakers.isNotEmpty()) " · " + stringResource(R.string.start_speakers_count, lecture.speakers.size) else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LastLecture(lecture: Lecture, compressing: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.start_last, lecture.title, formatClock(lecture.durationMillis), lecture.notes.size, lecture.audioFile),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (compressing != null && lecture.audioFile != AUDIO_FLAC) {
            Text(stringResource(R.string.compressing_flac), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { compressing }, modifier = Modifier.fillMaxWidth())
        }
    }
}
