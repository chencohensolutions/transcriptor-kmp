package com.chencohensolution.transcriptor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Segment
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

// Parts every option of the lecture screen (while recording) uses: the state the shared logic will produce, the
// actions, the timeline and the faked recording. Shaped as the controller would produce them.

/** The lecture screen while recording. */
data class LiveLectureState(
    val notebookTitle: String,
    val lecture: Lecture,
    /** The line live transcription is writing now, before its end; null in a pause between lines. */
    val partial: String?,
    val elapsedMillis: Long,
    /** The microphone's level, 0..1, for the meter. */
    val level: Float,
    val paused: Boolean,
    val liveModelName: String,
    /** Live transcription is this far behind the audio (it catches up in pauses). */
    val lagMillis: Long,
)

class LiveLectureActions(
    val back: () -> Unit = {},
    val pauseOrResume: () -> Unit = {},
    val stop: () -> Unit = {},
    /** A note pinned to [atMillis], when the user started typing it. */
    val addNote: (atMillis: Long, text: String) -> Unit = { _, _ -> },
    val openSegment: (Segment) -> Unit = {},
)

/** A line of speech or a note, in the order they happened. */
sealed interface TimelineEntry {
    val atMillis: Long

    data class Speech(val segment: Segment) : TimelineEntry {
        override val atMillis get() = segment.startMillis
    }

    data class Written(val note: Note) : TimelineEntry {
        override val atMillis get() = note.atMillis
    }
}

/** Lines and notes in order; a note comes right after the line that was being said when its typing started. */
fun timelineOf(lecture: Lecture): List<TimelineEntry> {
    fun lineStartAt(at: Long) = lecture.segments.lastOrNull { it.startMillis <= at }?.startMillis ?: 0L
    return (lecture.segments.map { TimelineEntry.Speech(it) } + lecture.notes.map { TimelineEntry.Written(it) })
        .sortedWith(compareBy({ lineStartAt(it.atMillis) }, { it is TimelineEntry.Written }, { it.atMillis }))
}

/**
 * Fakes for the lab: the clock runs, the level moves, the partial line grows a word at a time, and notes and pause
 * work, all in memory.
 */
@Composable
fun rememberLiveLectureLab(): Pair<LiveLectureState, LiveLectureActions> {
    var lecture by remember { mutableStateOf(LabData.lecture) }
    var elapsed by remember { mutableStateOf(LabData.lecture.durationMillis) }
    var paused by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf(1) }
    var level by remember { mutableStateOf(0.4f) }
    val partialWords = LabData.partialLine.split(" ")
    LaunchedEffect(paused) {
        var tick = 0
        while (!paused) {
            delay(100)
            tick++
            elapsed += 100
            level = (0.25f + 0.6f * abs(sin(tick * 0.7f) * sin(tick * 0.23f))).coerceIn(0f, 1f)
            if (tick % 6 == 0) words = if (words >= partialWords.size) 1 else words + 1
        }
    }
    val state = LiveLectureState(
        notebookTitle = LabData.notebooks.first().title,
        lecture = lecture,
        partial = if (paused) null else partialWords.take(words).joinToString(" "),
        elapsedMillis = elapsed,
        level = if (paused) 0f else level,
        paused = paused,
        liveModelName = "ivrit.ai turbo",
        lagMillis = 1_800,
    )
    val actions = LiveLectureActions(
        pauseOrResume = { paused = !paused },
        addNote = { at, text -> lecture = lecture.copy(notes = lecture.notes + Note("n${lecture.notes.size + 1}", at, text)) },
    )
    return state to actions
}

/** A red dot that breathes while recording, and stays still when paused. */
@Composable
fun RecordingDot(paused: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalTranscriptorColors.current
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    Box(modifier.size(10.dp).alpha(if (paused) 1f else pulse).clip(CircleShape).background(if (paused) colors.time else colors.recording))
}

/** Five bars that follow the microphone's level. */
@Composable
fun LevelBars(level: Float, modifier: Modifier = Modifier, height: Int = 18) {
    val colors = LocalTranscriptorColors.current
    Row(modifier.height(height.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0.5f, 0.8f, 1f, 0.75f, 0.45f).forEach { weight ->
            Box(
                Modifier.width(3.dp).fillMaxHeight((0.15f + level * weight).coerceAtMost(1f))
                    .clip(RoundedCornerShape(2.dp)).background(colors.recording.copy(alpha = 0.85f)),
            )
        }
    }
}
