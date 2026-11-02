package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import kotlinx.coroutines.delay

// Parts every option of the lecture-after screen uses: the state the shared logic will produce, the actions, and
// the fakes (playback, the refine pass, the speaker pass). Shaped as the controller would produce them.

/** A pass over the whole recording after the lecture: the refine pass (the large model) or the speaker pass. */
sealed interface PassState {
    data object NotRun : PassState
    data class Running(val progress: Float) : PassState
    data object Done : PassState
}

/** A recorded lecture, opened. */
data class ReviewState(
    val notebookTitle: String,
    val lecture: Lecture,
    val positionMillis: Long,
    val playing: Boolean,
    val speed: Float,
    /** The model the refine pass would use ("ivrit.ai Whisper large-v3"). */
    val refineModelName: String,
    val refine: PassState,
    val speakerPass: PassState,
) {
    /** The line being played (or last played): the one the position is in, else the one before it. */
    val currentLine: Segment? get() = lecture.segments.lastOrNull { it.startMillis <= positionMillis }
}

class ReviewActions(
    val back: () -> Unit = {},
    val playPause: () -> Unit = {},
    val seek: (atMillis: Long) -> Unit = {},
    /** Plays from a line's start. */
    val playLine: (Segment) -> Unit = {},
    val cycleSpeed: () -> Unit = {},
    /** The user's text for a line: marked edited, kept by the refine pass. */
    val editLine: (id: String, text: String) -> Unit = { _, _ -> },
    /** Moves a line to another speaker. */
    val setLineSpeaker: (lineId: String, speakerId: String) -> Unit = { _, _ -> },
    val renameSpeaker: (id: String, name: String) -> Unit = { _, _ -> },
    val refine: () -> Unit = {},
    val findSpeakers: () -> Unit = {},
    val addNote: (atMillis: Long, text: String) -> Unit = { _, _ -> },
    val editNote: (id: String, text: String) -> Unit = { _, _ -> },
    val deleteNote: (id: String) -> Unit = {},
)

/** The color of a speaker, by order of speaking. */
@Composable
fun speakerColor(id: String?): Color {
    val palette = LocalTranscriptorColors.current.speakers
    if (id == null) return LocalTranscriptorColors.current.time
    return palette[((id.removePrefix("speaker-").toIntOrNull() ?: 1) - 1).mod(palette.size)]
}

/** A speaker's name in their color. */
@Composable
fun ReviewSpeakerChip(speaker: Speaker, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val color = speakerColor(speaker.id)
    Text(
        speakerLabel(speaker),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Fakes for the lab: playback (a clock that runs at the speed), the refine pass (six seconds, then the lines as the
 * large model wrote them, edited lines kept) and the speaker pass (four seconds, then the student's question split
 * off and named speakers kept); edits, notes and renames in memory.
 */
@Composable
fun rememberReviewLab(): Pair<ReviewState, ReviewActions> {
    // The sample's ten lines are its first three minutes: the lecture ends there, so the timeline is full.
    var lecture by remember { mutableStateOf(LabData.liveLectureWithSpeakers.copy(notes = LabData.lecture.notes, durationMillis = 195_000)) }
    var position by remember { mutableStateOf(65_000L) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var refine by remember { mutableStateOf<PassState>(PassState.NotRun) }
    var speakers by remember { mutableStateOf<PassState>(PassState.NotRun) }
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
    }
    val state = ReviewState(
        notebookTitle = LabData.notebooks.first().title, lecture = lecture, positionMillis = position, playing = playing,
        speed = speed, refineModelName = "ivrit.ai Whisper large-v3", refine = refine, speakerPass = speakers,
    )
    val actions = ReviewActions(
        playPause = { if (position >= lecture.durationMillis) position = 0; playing = !playing },
        seek = { position = it.coerceIn(0, lecture.durationMillis) },
        playLine = { position = it.startMillis; playing = true },
        cycleSpeed = { speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f } },
        editLine = { id, text -> lecture = lecture.copy(segments = lecture.segments.map { if (it.id == id) it.copy(text = text.trim(), edited = true) else it }) },
        setLineSpeaker = { line, speaker -> lecture = lecture.copy(segments = lecture.segments.map { if (it.id == line) it.copy(speakerId = speaker) else it }) },
        renameSpeaker = { id, name -> lecture = lecture.copy(speakers = lecture.speakers.map { if (it.id == id) it.copy(name = name.trim()) else it }) },
        refine = { if (refine !is PassState.Running) refine = PassState.Running(0f) },
        findSpeakers = { if (speakers !is PassState.Running) speakers = PassState.Running(0f) },
        addNote = { at, text -> lecture = lecture.copy(notes = (lecture.notes + Note("n${lecture.notes.size + 1}", at, text.trim())).sortedBy { it.atMillis }) },
        editNote = { id, text -> lecture = lecture.copy(notes = lecture.notes.map { if (it.id == id) it.copy(text = text.trim()) else it }) },
        deleteNote = { id -> lecture = lecture.copy(notes = lecture.notes.filter { it.id != id }) },
    )
    return state to actions
}
