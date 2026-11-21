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
import com.chencohensolution.transcriptor.models.ModelStrength
import com.chencohensolution.transcriptor.review.ModelChoice
import com.chencohensolution.transcriptor.review.ModelToGet
import com.chencohensolution.transcriptor.review.PassState
import com.chencohensolution.transcriptor.review.ReviewState
import com.chencohensolution.transcriptor.review.mergingSpeakers
import com.chencohensolution.transcriptor.review.onlyOf
import kotlinx.coroutines.delay

// Fakes: playback (a clock; no audio), the improve pass and the speaker pass (progress, then made-up results), the
// lecture's speakers and lines, the models to improve with and their estimates (as if each had run once on this
// phone; the sample is three minutes long). Seeking, playing a sentence, editing, moving a line, renaming, notes and
// the speed work in memory. The Word file is the app's; Share opens the share sheet with it and Save as Word saves it
// in Downloads/Transcriptor (the lab has no lecture folders). Delete lecture only says so.
internal val reviewCurrent = LabOption("current", "Document + player below (the app's)", "ReviewScreen with faked playback and passes") {
    val (state, actions) = rememberReviewLab(modelsDownloaded = true)
    ReviewScreen(state, actions, allLabels = LabData.labels)
}

// Fakes: as `current`, with no model to improve Hebrew with downloaded: Improve transcription… offers the one to get.
internal val reviewCurrentNoModel = LabOption("current-none", "The app's, nothing to improve with", "Improve transcription… with no model downloaded") {
    val (state, actions) = rememberReviewLab(modelsDownloaded = false)
    ReviewScreen(state, actions)
}

// Fakes: as `current`, for a moment that was only recorded (After on the Record bar): no lines until Transcribe now,
// which takes six seconds and then shows the sample's lines; its notes are there from the start.
internal val reviewCurrentWaiting = LabOption("current-waiting", "The app's, recorded and not transcribed yet", "A moment recorded with After: Transcribe now, then its lines") {
    val (state, actions) = rememberReviewLab(modelsDownloaded = true, waiting = true)
    ReviewScreen(state, actions)
}

// Fakes: as `current-people` without the sheet: the conversation as it reads, short turns compact, people as playback
// filters above the player, the live-draft line with Improve.
internal val reviewCurrentConversation = LabOption("current-conversation", "The app's, a conversation of four", "ReviewScreen on LabData.conversation: compact turns, play one person, share as text") {
    val (state, actions) = rememberReviewLab(modelsDownloaded = true, sample = LabData.conversation)
    ReviewScreen(state, actions)
}

// Fakes: as `current-conversation`, its lines shared among twelve made-up speakers: the player panel's lanes scroll
// under the people row, and the panel starts folded (its handle opens it).
internal val reviewCurrentCrowd = LabOption("current-crowd", "The app's, twelve speakers", "ReviewScreen with twelve speakers: the folding, scrolling player panel") {
    val crowd = remember {
        val c = LabData.conversation
        c.copy(
            speakers = List(12) { Speaker("speaker-${it + 1}", if (it == 0) "Hen" else "") },
            segments = c.segments.mapIndexed { i, l -> l.copy(speakerId = "speaker-${i % 12 + 1}") },
        )
    }
    val (state, actions) = rememberReviewLab(modelsDownloaded = true, sample = crowd)
    ReviewScreen(state, actions)
}

// Fakes: as `current`, with an English conversation of four (LabData.conversation) as it opens after Stop: "Who was
// here?" comes up by itself. Names, merging and the samples work in memory (a sample moves the playhead; no audio);
// the known voices offered are made up.
internal val reviewCurrentPeople = LabOption("current-people", "The app's, a conversation of four: Who was here?", "ReviewScreen on an English conversation, the people sheet open") {
    val (state, actions) = rememberReviewLab(modelsDownloaded = true, sample = LabData.conversation)
    ReviewScreen(state, actions, askPeople = true)
}

/**
 * Fakes for the lab: playback (a clock that runs at the speed), the improve pass (six seconds, then the lines as the
 * large model wrote them, edited lines kept) and the speaker pass (four seconds, then the student's question split
 * off and named speakers kept); edits, notes and renames in memory.
 */
@Composable
private fun rememberReviewLab(modelsDownloaded: Boolean, waiting: Boolean = false, sample: com.chencohensolution.transcriptor.notebook.Lecture? = null): Pair<ReviewState, ReviewActions> {
    val context = LocalContext.current
    // The sample's ten lines are its first three minutes: the lecture ends there, so the timeline is full.
    val recorded = sample ?: LabData.liveLectureWithSpeakers.copy(notes = LabData.lecture.notes, durationMillis = 195_000)
    var lecture by remember { mutableStateOf(if (waiting) recorded.copy(segments = emptyList(), speakers = emptyList(), pendingTranscript = true) else recorded) }
    var position by remember { mutableStateOf(sample?.segments?.firstOrNull()?.startMillis ?: 65_000L) }
    var playing by remember { mutableStateOf(false) }
    var only by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(1f) }
    var refine by remember { mutableStateOf<PassState>(PassState.NotRun) }
    var speakers by remember { mutableStateOf<PassState>(PassState.NotRun) }
    var thenImprove by remember { mutableStateOf(false) }
    LaunchedEffect(playing, speed) {
        while (playing) {
            delay(100)
            position = (position + (100 * speed).toLong()).coerceAtMost(lecture.durationMillis)
            only?.let { o -> when (val to = lecture.onlyOf(o, position)) { null -> playing = false; else -> position = to } }
            if (position >= lecture.durationMillis) playing = false
        }
    }
    LaunchedEffect(refine is PassState.Running) {
        if (refine !is PassState.Running) return@LaunchedEffect
        for (i in 1..60) { delay(100); refine = PassState.Running(i / 60f) }
        val large = LabData.refinedLecture.segments.associateBy { it.id }
        if (lecture.pendingTranscript) lecture = lecture.copy(segments = recorded.segments, speakers = recorded.speakers, pendingTranscript = false)
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
    // Fakes: an earlier transcript kept as a version (the Zipformer's, live), a few words heard otherwise, for Compare.
    val earlier = remember(lecture.id) {
        com.chencohensolution.transcriptor.notebook.VersionInfo("v-lab", ModelCatalog.ivritZipformerNpu.id, 1_763_300_000_000L, lecture.segments.size, 0, refined = false)
    }
    val earlierLines = remember(lecture.id) {
        lecture.segments.mapIndexed { i, l -> if (i % 3 == 1) l.copy(text = l.text.split(' ').mapIndexed { j, w -> if (j == 2) w.reversed() else w }.joinToString(" ")) else l }
    }
    val state = ReviewState(
        labelNames = if (sample == null) com.chencohensolution.transcriptor.notebook.labelNames(lecture.labels, LabData.labels) else "", onlySpeaker = only, knownNames = if (sample == null) emptyList() else LabData.knownNames, lecture = lecture.copy(versions = lecture.versions.ifEmpty { listOf(earlier) }), positionMillis = position, playing = playing,
        speed = speed, refine = refine, speakerPass = speakers,
        refineModels = if (!modelsDownloaded) emptyList() else listOf(
            ModelChoice(ModelCatalog.ivritLargeV3Npu.id, ModelCatalog.ivritLargeV3Npu.name, minutes = 1, npu = true),
            ModelChoice(ModelCatalog.whisperLargeV3.id, ModelCatalog.whisperLargeV3.name, minutes = 4),
            ModelChoice(ModelCatalog.ivritTurboNpu.id, ModelCatalog.ivritTurboNpu.name, npu = true, strength = ModelStrength.Fast),
            ModelChoice(ModelCatalog.ivritTurbo.id, ModelCatalog.ivritTurbo.name, minutes = 1, strength = ModelStrength.Fast),
            ModelChoice(ModelCatalog.ivritZipformerNpu.id, ModelCatalog.ivritZipformerNpu.name, minutes = 1, npu = true, strength = ModelStrength.Streaming),
        ),
        improveDefault = ModelCatalog.ivritLargeV3Npu.id.takeIf { modelsDownloaded },
        improveToGet = ModelToGet(ModelCatalog.ivritLargeV3.id, ModelCatalog.ivritLargeV3.name, ModelCatalog.ivritLargeV3.sizeBytes).takeIf { !modelsDownloaded },
    )
    val labBack = LocalLabBack.current
    val actions = ReviewActions(
        // Fakes: the moment's power reports (a 25-minute AAC recording from earbuds, then nothing encoded afterwards) and its file size.
        info = {
            val audio = com.chencohensolution.transcriptor.recording.PowerAudio("AacLive", "aac", "c2.android.aac.encoder", hardware = false, details = "LC · 256 kbps · stereo · 48 kHz", input = "Galaxy Buds3 Pro")
            com.chencohensolution.transcriptor.review.MomentInfo(
                lecture.copy(audioFile = "audio.aac"), 48_400_000L,
                com.chencohensolution.transcriptor.recording.PowerReport(
                    durationMillis = 1_500_000, samples = 150, averageMilliwatts = 612.0, totalMilliwattHours = 255.0, milliwattHoursPerMinute = 10.2,
                    joulesPerMinute = 36.7, batteryPercentStart = 84, batteryPercentEnd = 80, temperatureStart = 31.2, temperatureEnd = 33.0,
                    cpuSecondsPerMinute = 2.4, screenOnShare = 0.08, charging = false, audio = audio, batteryPercentUsed = 4, batteryPercentPerHour = 9.6,
                ),
                null,
            )
        },
        back = labBack,
        version = { com.chencohensolution.transcriptor.notebook.Version(earlier, earlierLines, lecture.speakers, false, null) },
        playPause = { if (position >= lecture.durationMillis) position = 0; playing = !playing },
        seek = { position = it.coerceIn(0, lecture.durationMillis) },
        playLine = { position = it.startMillis; playing = true },
        cycleSpeed = { speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f } },
        editLine = { id, text -> lecture = lecture.copy(segments = lecture.segments.map { if (it.id == id) it.copy(text = text.trim(), edited = true) else it }) },
        setLineSpeaker = { line, speaker -> lecture = lecture.copy(segments = lecture.segments.map { if (it.id == line) it.copy(speakerId = speaker) else it }) },
        renameSpeaker = { id, name -> lecture = lecture.copy(speakers = lecture.speakers.map { if (it.id == id) it.copy(name = name.trim()) else it }) },
        namePerson = { id, name ->
            val same = lecture.speakers.firstOrNull { it.id != id && it.name.isNotEmpty() && it.name.equals(name.trim(), ignoreCase = true) }
            lecture = if (same != null) lecture.mergingSpeakers(id, same.id) else lecture.copy(speakers = lecture.speakers.map { if (it.id == id) it.copy(name = name.trim()) else it })
        },
        mergeSpeakers = { from, into -> lecture = lecture.mergingSpeakers(from, into) },
        playOnly = { id -> only = id.takeIf { it != only }; only?.let { o -> lecture.onlyOf(o, position)?.let { position = it; playing = true } } },
        shareText = { Toast.makeText(context, com.chencohensolution.transcriptor.notebook.transcriptText(lecture).take(200), Toast.LENGTH_LONG).show() },
        playPerson = { id -> lecture.segments.filter { it.speakerId == id }.maxByOrNull { it.endMillis - it.startMillis }?.let { position = it.startMillis; playing = true } },
        rename = { title -> lecture = lecture.copy(title = title.trim()) },
        setLabels = { ids -> lecture = lecture.copy(labels = ids) },
        improve = { _, first ->
            if (first) { thenImprove = true; speakers = PassState.Running(0f) } else if (refine !is PassState.Running) refine = PassState.Running(0f)
        },
        switchVersion = {},
        findSpeakers = { if (speakers !is PassState.Running) speakers = PassState.Running(0f) },
        addNote = { at, text -> lecture = lecture.copy(notes = (lecture.notes + Note("n${lecture.notes.size + 1}", at, text.trim())).sortedBy { it.atMillis }) },
        editNote = { id, text -> lecture = lecture.copy(notes = lecture.notes.map { if (it.id == id) it.copy(text = text.trim()) else it }) },
        deleteNote = { id -> lecture = lecture.copy(notes = lecture.notes.filter { it.id != id }) },
        shareWord = { val name = docxName(lecture); LabWord.share(context, LabWord.save(context, name, transcriptDocx(lecture, LabData.labels.first().name)), name) },
        saveWord = {
            LabWord.save(context, docxName(lecture), transcriptDocx(lecture, LabData.labels.first().name))
            Toast.makeText(context, "Lab: saved in Downloads/Transcriptor (the app: beside the recording)", Toast.LENGTH_LONG).show()
        },
        deleteLecture = { Toast.makeText(context, "Lab: deleted; the list would open with it gone", Toast.LENGTH_LONG).show() },
    )
    return state to actions
}

// Fakes: as `current-conversation`, paused twice while recording: 12 minutes after its third turn, and 40 seconds
// later on. The clock times are made up from the moment's start.
internal val reviewCurrentPaused = LabOption("current-paused", "The app's, a conversation paused twice", "Pause dividers between the turns, and in Info") {
    val (state, actions) = rememberReviewLab(modelsDownloaded = true, sample = LabData.conversation)
    val l = state.lecture
    val third = l.segments.getOrNull(2)?.endMillis ?: 20_000
    val later = l.segments.getOrNull(6)?.endMillis ?: 60_000
    val pauses = listOf(
        com.chencohensolution.transcriptor.notebook.Pause(third, l.startedAtMillis + third, l.startedAtMillis + third + 12 * 60_000),
        com.chencohensolution.transcriptor.notebook.Pause(later, l.startedAtMillis + later + 12 * 60_000, l.startedAtMillis + later + 12 * 60_000 + 40_000),
    )
    ReviewScreen(state.copy(lecture = l.copy(pauses = pauses)), actions)
}
