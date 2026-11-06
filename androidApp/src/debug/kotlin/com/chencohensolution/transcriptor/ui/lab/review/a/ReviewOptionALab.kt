package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.review.PassState
import com.chencohensolution.transcriptor.review.ReviewState
import kotlinx.coroutines.delay

// Fakes: "Improve automatically" is off (in the app today that hides Refine with…); the estimates (made up, as the
// app would measure each model's speed on this phone); playback (a clock, no audio); the improve pass (six seconds,
// then the large model's lines, edited lines kept); the models downloaded are those of `models a` and `b`; the speaker pass (four seconds). The lecture is the sample's first
// three minutes, so the estimates are short: large-v3 takes about 0.4× the lecture's length on the NPU,
// 1.4× on the CPU.
internal val reviewA = LabOption("a", "Improve transcription… always in the menu", "A sheet: models with estimates, the Settings default picked, speakers first") {
    ReviewLabA(modelsDownloaded = true)
}

// Fakes: as `a`, with no model for Hebrew downloaded: the sheet offers the one to get (tapping it does nothing here).
internal val reviewANone = LabOption("a-none", "Improve transcription… with no model downloaded", "The sheet offers ivrit.ai large-v3 to download") {
    ReviewLabA(modelsDownloaded = false)
}

@Composable
private fun ReviewLabA(modelsDownloaded: Boolean) {
    var lecture by remember { mutableStateOf(LabData.liveLectureWithSpeakers.copy(notes = LabData.lecture.notes, durationMillis = 195_000)) }
    var position by remember { mutableStateOf(65_000L) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var improve by remember { mutableStateOf<PassState>(PassState.NotRun) }
    var speakers by remember { mutableStateOf<PassState>(PassState.NotRun) }
    var thenImprove by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playing, speed) {
        while (playing) {
            delay(100)
            position = (position + (100 * speed).toLong()).coerceAtMost(lecture.durationMillis)
            if (position >= lecture.durationMillis) playing = false
        }
    }
    LaunchedEffect(speakers is PassState.Running) {
        if (speakers !is PassState.Running) return@LaunchedEffect
        for (i in 1..40) { delay(100); speakers = PassState.Running(i / 40f) }
        lecture = lecture.copy(segments = lecture.segments.map { if (it.id == "s9") it.copy(speakerId = "speaker-3") else it }, speakers = lecture.speakers + Speaker("speaker-3", ""))
        speakers = PassState.Done
        if (thenImprove != null) { thenImprove = null; improve = PassState.Running(0f) }
    }
    LaunchedEffect(improve is PassState.Running) {
        if (improve !is PassState.Running) return@LaunchedEffect
        for (i in 1..60) { delay(100); improve = PassState.Running(i / 60f) }
        val large = LabData.refinedLecture.segments.associateBy { it.id }
        lecture = lecture.copy(segments = lecture.segments.map { s -> if (s.edited) s else s.copy(text = large[s.id]?.text ?: s.text, modelId = large[s.id]?.modelId ?: s.modelId) })
        improve = PassState.Done
    }
    val state = ReviewState(
        notebookTitle = LabData.notebooks.first().title, lecture = lecture, positionMillis = position, playing = playing,
        speed = speed, refineModels = emptyList(), refine = improve, speakerPass = speakers,
    )
    val minutes = { factor: Double -> (lecture.durationMillis / 60_000.0 * factor).toInt().coerceAtLeast(1) }
    val options = ImproveOptionsA(
        choices = if (!modelsDownloaded) emptyList() else listOf(
            ImproveChoiceA(ModelCatalog.ivritLargeV3Npu.id, ModelCatalog.ivritLargeV3Npu.name, minutes(0.4), npu = true),
            ImproveChoiceA(ModelCatalog.ivritTurboNpu.id, ModelCatalog.ivritTurboNpu.name, minutes(0.1), npu = true),
            ImproveChoiceA(ModelCatalog.ivritTurbo.id, ModelCatalog.ivritTurbo.name, minutes(0.25), npu = false),
            ImproveChoiceA(ModelCatalog.whisperLargeV3.id, ModelCatalog.whisperLargeV3.name, minutes(1.4), npu = false),
        ),
        defaultId = ModelCatalog.ivritLargeV3Npu.id.takeIf { modelsDownloaded },
        suggested = ModelCatalog.ivritLargeV3.name to modelsSize(ModelCatalog.ivritLargeV3.sizeBytes),
        offerSpeakersFirst = speakers == PassState.NotRun,
    )
    ReviewScreenA(
        state,
        options,
        ReviewActions(
            playPause = { if (position >= lecture.durationMillis) position = 0; playing = !playing },
            seek = { position = it.coerceIn(0, lecture.durationMillis) },
            playLine = { position = it.startMillis; playing = true },
            cycleSpeed = { speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f } },
            editLine = { id, text -> lecture = lecture.copy(segments = lecture.segments.map { if (it.id == id) it.copy(text = text.trim(), edited = true) else it }) },
            setLineSpeaker = { line, speaker -> lecture = lecture.copy(segments = lecture.segments.map { if (it.id == line) it.copy(speakerId = speaker) else it }) },
            renameSpeaker = { id, name -> lecture = lecture.copy(speakers = lecture.speakers.map { if (it.id == id) it.copy(name = name.trim()) else it }) },
            findSpeakers = { if (speakers !is PassState.Running) speakers = PassState.Running(0f) },
        ),
        ImproveActionsA(
            improve = { id, first -> if (first) { thenImprove = id; speakers = PassState.Running(0f) } else improve = PassState.Running(0f) },
        ),
    )
}
