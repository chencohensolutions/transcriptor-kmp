package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.recording.LiveLectureState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

// Fakes: the recording (clock, level, the partial line growing), live transcription, speaker labels (a lecturer the
// notebook knew and an unnamed student) and the lag figure; Stop and tapping a line do nothing. Notes, pause and
// renaming a speaker work in memory.
internal val lectureCurrent = LabOption("current", "One timeline (the app's)", "LectureScreen with a faked recording and live transcription") {
    val (state, actions) = rememberLiveLectureLab()
    LectureScreen(state, actions)
}

/**
 * The clock runs, the level moves, the partial line grows a word at a time, and notes and pause work, all in
 * memory.
 */
@Composable
private fun rememberLiveLectureLab(): Pair<LiveLectureState, LiveLectureActions> {
    var lecture by remember { mutableStateOf(LabData.liveLectureWithSpeakers) }
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
        renameSpeaker = { id, name -> lecture = lecture.copy(speakers = lecture.speakers.map { if (it.id == id) it.copy(name = name) else it }) },
    )
    return state to actions
}
