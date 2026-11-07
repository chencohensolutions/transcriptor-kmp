package com.chencohensolution.transcriptor.ui

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.chencohensolution.transcriptor.notebook.docxName
import com.chencohensolution.transcriptor.notebook.transcriptDocx
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.review.ModelChoice
import com.chencohensolution.transcriptor.review.ModelToGet
import com.chencohensolution.transcriptor.review.PassState
import com.chencohensolution.transcriptor.review.ReviewState
import kotlinx.coroutines.delay

// Fakes: playback (a clock; no audio), the improve pass and the speaker pass (progress, then made-up results), the
// lecture's speakers and lines, the models to improve with and their estimates (as if each had run once on this
// phone; the sample is three minutes long). Seeking, playing a sentence, editing, moving a line, renaming, notes and
// the speed work in memory. The Word file is the app's; Share opens the share sheet with it and Save as Word saves it
// in Downloads/Transcriptor (the lab has no lecture folders). Delete lecture only says so.
internal val reviewCurrent = LabOption("current", "Document + player below (the app's)", "ReviewScreen with faked playback and passes") {
    val (state, actions) = rememberReviewLab(modelsDownloaded = true)
    ReviewScreen(state, actions)
}

// Fakes: as `current`, with no model to improve Hebrew with downloaded: Improve transcription… offers the one to get.
internal val reviewCurrentNoModel = LabOption("current-none", "The app's, nothing to improve with", "Improve transcription… with no model downloaded") {
    val (state, actions) = rememberReviewLab(modelsDownloaded = false)
    ReviewScreen(state, actions)
}

/**
 * Fakes for the lab: playback (a clock that runs at the speed), the improve pass (six seconds, then the lines as the
 * large model wrote them, edited lines kept) and the speaker pass (four seconds, then the student's question split
 * off and named speakers kept); edits, notes and renames in memory.
 */
@Composable
private fun rememberReviewLab(modelsDownloaded: Boolean): Pair<ReviewState, ReviewActions> {
    val context = LocalContext.current
    // The sample's ten lines are its first three minutes: the lecture ends there, so the timeline is full.
    var lecture by remember { mutableStateOf(LabData.liveLectureWithSpeakers.copy(notes = LabData.lecture.notes, durationMillis = 195_000)) }
    var position by remember { mutableStateOf(65_000L) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var refine by remember { mutableStateOf<PassState>(PassState.NotRun) }
    var speakers by remember { mutableStateOf<PassState>(PassState.NotRun) }
    var thenImprove by remember { mutableStateOf(false) }
    LaunchedEffect(playing, speed) {
        while (playing) {
            delay(100)
            position = (position + (100 * speed).toLong()).coerceAtMost(lecture.durationMillis)
            if (position >= lecture.durationMillis) playing = false
        }
    }
    LaunchedEffect(refine is PassState.Running) {
        if (refine !is PassState.Running) return@LaunchedEffect
        for (i in 1..60) { delay(100); refine = PassState.Running(i / 60f) }
        val large = LabData.refinedLecture.segments.associateBy { it.id }
        lecture = lecture.copy(segments = lecture.segments.map { s -> if (s.edited) s else s.copy(text = large[s.id]?.text ?: s.text, modelId = large[s.id]?.modelId ?: s.modelId) })
        refine = PassState.Done
    }
    LaunchedEffect(speakers is PassState.Running) {
        if (speakers !is PassState.Running) return@LaunchedEffect
        for (i in 1..40) { delay(100); speakers = PassState.Running(i / 40f) }
        lecture = lecture.copy(segments = lecture.segments.map { if (it.id == "s9") it.copy(speakerId = "speaker-3") else it }, speakers = lecture.speakers + Speaker("speaker-3", ""))
        speakers = PassState.Done
        if (thenImprove) { thenImprove = false; refine = PassState.Running(0f) }
    }
    val state = ReviewState(
        notebookTitle = LabData.notebooks.first().title, lecture = lecture, positionMillis = position, playing = playing,
        speed = speed, refine = refine, speakerPass = speakers,
        refineModels = if (!modelsDownloaded) emptyList() else listOf(
            ModelChoice(ModelCatalog.ivritLargeV3Npu.id, ModelCatalog.ivritLargeV3Npu.name, minutes = 1, npu = true),
            ModelChoice(ModelCatalog.whisperLargeV3.id, ModelCatalog.whisperLargeV3.name, minutes = 4),
            ModelChoice(ModelCatalog.ivritTurboNpu.id, ModelCatalog.ivritTurboNpu.name, npu = true),
            ModelChoice(ModelCatalog.ivritTurbo.id, ModelCatalog.ivritTurbo.name, minutes = 1),
        ),
        improveDefault = ModelCatalog.ivritLargeV3Npu.id.takeIf { modelsDownloaded },
        improveToGet = ModelToGet(ModelCatalog.ivritLargeV3.id, ModelCatalog.ivritLargeV3.name, ModelCatalog.ivritLargeV3.sizeBytes).takeIf { !modelsDownloaded },
    )
    val actions = ReviewActions(
        playPause = { if (position >= lecture.durationMillis) position = 0; playing = !playing },
        seek = { position = it.coerceIn(0, lecture.durationMillis) },
        playLine = { position = it.startMillis; playing = true },
        cycleSpeed = { speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f } },
        editLine = { id, text -> lecture = lecture.copy(segments = lecture.segments.map { if (it.id == id) it.copy(text = text.trim(), edited = true) else it }) },
        setLineSpeaker = { line, speaker -> lecture = lecture.copy(segments = lecture.segments.map { if (it.id == line) it.copy(speakerId = speaker) else it }) },
        renameSpeaker = { id, name -> lecture = lecture.copy(speakers = lecture.speakers.map { if (it.id == id) it.copy(name = name.trim()) else it }) },
        improve = { _, first ->
            if (first) { thenImprove = true; speakers = PassState.Running(0f) } else if (refine !is PassState.Running) refine = PassState.Running(0f)
        },
        switchVersion = {},
        findSpeakers = { if (speakers !is PassState.Running) speakers = PassState.Running(0f) },
        addNote = { at, text -> lecture = lecture.copy(notes = (lecture.notes + Note("n${lecture.notes.size + 1}", at, text.trim())).sortedBy { it.atMillis }) },
        editNote = { id, text -> lecture = lecture.copy(notes = lecture.notes.map { if (it.id == id) it.copy(text = text.trim()) else it }) },
        deleteNote = { id -> lecture = lecture.copy(notes = lecture.notes.filter { it.id != id }) },
        shareWord = { val name = docxName(lecture); LabWord.share(context, LabWord.save(context, name, transcriptDocx(lecture, LabData.notebooks.first().title)), name) },
        saveWord = {
            LabWord.save(context, docxName(lecture), transcriptDocx(lecture, LabData.notebooks.first().title))
            Toast.makeText(context, "Lab: saved in Downloads/Transcriptor (the app: beside the recording)", Toast.LENGTH_LONG).show()
        },
        deleteLecture = { Toast.makeText(context, "Lab: deleted; the list would open with it gone", Toast.LENGTH_LONG).show() },
    )
    return state to actions
}
