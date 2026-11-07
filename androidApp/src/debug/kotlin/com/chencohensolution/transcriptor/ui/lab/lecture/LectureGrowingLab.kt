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
import com.chencohensolution.transcriptor.recording.ModelInUse
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * A live lecture that keeps growing, for the lecture screen's lab. It starts with LabData's first four lines, then "says" the rest of LabData's lines a word at a time, at
 * a speaker's uneven pace, each becoming a finished line when its last word is in; after the last line it says them
 * again (new ids, later times), so lines keep being created. Notes, pause, renaming and editing a line work in memory.
 */
@Composable
internal fun rememberGrowingLectureLab(): Pair<LiveLectureState, LiveLectureActions> {
    val source = LabData.liveLectureWithSpeakers
    val toSay = source.segments.drop(4)
    val start = source.segments[3].endMillis + 500
    // LabData's notes come in as the clock passes their time.
    var lecture by remember { mutableStateOf(source.copy(segments = source.segments.take(4), notes = source.notes.filter { it.atMillis < start })) }
    var elapsed by remember { mutableStateOf(start) }
    var paused by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0.4f) }
    var saying by remember { mutableStateOf(0) }
    var round by remember { mutableStateOf(0) }
    var words by remember { mutableStateOf(0) }
    LaunchedEffect(paused) {
        var tick = 0
        var nextWordIn = 3
        while (!paused) {
            delay(100)
            tick++
            elapsed += 100
            level = (0.25f + 0.6f * abs(sin(tick * 0.7f) * sin(tick * 0.23f))).coerceIn(0f, 1f)
            source.notes.firstOrNull { it.atMillis <= elapsed && lecture.notes.none { n -> n.id == it.id } && round == 0 }
                ?.let { lecture = lecture.copy(notes = lecture.notes + it) }
            if (--nextWordIn > 0) continue
            val line = toSay[saying]
            val all = line.text.split(" ")
            if (words < all.size) {
                words++
                // A speaker's pace: most words close together, a few after a short pause.
                nextWordIn = if (Random.nextFloat() < 0.15f) 7 + Random.nextInt(5) else 2 + Random.nextInt(3)
            } else {
                val from = (lecture.segments.lastOrNull()?.endMillis ?: 0) + 600
                val id = if (round == 0) line.id else "${line.id}r$round"
                lecture = lecture.copy(segments = lecture.segments + line.copy(id = id, startMillis = from, endMillis = elapsed))
                words = 0
                saying = (saying + 1) % toSay.size
                if (saying == 0) round++
                nextWordIn = 8
            }
        }
    }
    val partial = toSay[saying].text.split(" ").take(words).joinToString(" ").ifEmpty { null }
    val state = LiveLectureState(
        notebookTitle = LabData.notebooks.first().title,
        lecture = lecture,
        partial = if (paused) null else partial,
        elapsedMillis = elapsed,
        level = if (paused) 0f else level,
        paused = paused,
        liveModelName = "ivrit.ai turbo",
        lagMillis = 1_800,
        voicePrints = ModelInUse("WeSpeaker SimAM-ResNet100", ModelInUse.Runs.Npu),
        speakerPassOnStop = true,
    )
    val actions = LiveLectureActions(
        pauseOrResume = { paused = !paused },
        addNote = { at, text -> lecture = lecture.copy(notes = lecture.notes + Note("mine${lecture.notes.size + 1}", at, text)) },
        renameSpeaker = { id, name -> lecture = lecture.copy(speakers = lecture.speakers.map { if (it.id == id) it.copy(name = name) else it }) },
        editLine = { id, text ->
            lecture = lecture.copy(segments = lecture.segments.map { if (it.id == id && it.text != text.trim()) it.copy(text = text.trim(), edited = true) else it })
        },
    )
    return state to actions
}
