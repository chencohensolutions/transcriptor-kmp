package com.chencohensolution.transcriptor.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Notebook
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// Fakes: the notebooks (LabData) and the time: the Monday after its latest lecture at 9:50, ten minutes before the
// psychology class (c2), or Tuesday at 18:00, when no class usually is (c2-no-guess). Two lectures recorded without a
// notebook: Saturday 10:05 (statistics' time, so suggested there) and Thursday 18:30 (no suggestion). Recording is a
// clock (the banner stops it and lists the lecture, unfiled when recorded in none), assigning and new notebooks are in
// memory, a speaker pass runs on a lecture just stopped. The guess is the real guessNotebook on that data; the phone's
// time zone decides weekdays and hours. Opening a lecture and Settings only say so.
internal val notebooksOptionC2 = LabOption("c2", "Lectures first, record now, file later", "c, plus recording without a notebook (filed later, with a suggestion) and the notebook guessed from when its lectures are") {
    val (state, actions) = rememberNotebooksLabC2(now = MONDAY_BEFORE_CLASS)
    NotebooksScreenC2(state, actions)
}

internal val notebooksOptionC2NoGuess = LabOption("c2-no-guess", "Lectures first, record now, file later: no class now", "c2 on Tuesday evening: no notebook guessed, so Record files it later") {
    val (state, actions) = rememberNotebooksLabC2(now = TUESDAY_EVENING)
    NotebooksScreenC2(state, actions)
}

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN
private val LATEST = LabData.notebooks.first().lectures.first().startedAtMillis
private val MONDAY_BEFORE_CLASS = LATEST + 7 * DAY - 10 * MIN
private val TUESDAY_EVENING = LATEST + 8 * DAY + 8 * 60 * MIN

@Composable
private fun rememberNotebooksLabC2(now: Long): Pair<NotebooksStateC2, NotebooksActionsC2> {
    val context = LocalContext.current
    fun title(at: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
    var notebooks by remember { mutableStateOf(LabData.notebooks) }
    var unfiled by remember {
        mutableStateOf(
            listOf(
                LectureSummary("unfiled-sat", title(LATEST + 5 * DAY + 5 * MIN), LATEST + 5 * DAY + 5 * MIN, 88 * MIN, 4, refined = true),
                LectureSummary("unfiled-thu", title(LATEST + 3 * DAY + 510 * MIN), LATEST + 3 * DAY + 510 * MIN, 52 * MIN, 0, refined = true),
            ),
        )
    }
    var recording by remember { mutableStateOf<RecordingNow?>(null) }
    var working by remember { mutableStateOf(emptyMap<String, Float>()) }
    LaunchedEffect(recording != null) {
        while (recording != null) {
            delay(1_000)
            recording = recording?.let { it.copy(elapsedMillis = it.elapsedMillis + 1_000) }
        }
    }
    LaunchedEffect(working.isNotEmpty()) {
        while (working.isNotEmpty()) {
            delay(150)
            working = working.mapValues { (it.value + 0.02f) }.filterValues { it < 1f }
        }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun take(lectureId: String): LectureSummary? = unfiled.firstOrNull { it.id == lectureId }?.also { l -> unfiled = unfiled.filter { it.id != l.id } }
    fun file(lecture: LectureSummary, notebookId: String) {
        notebooks = notebooks.map { n -> if (n.id == notebookId) n.copy(lectures = (n.lectures + lecture).sortedByDescending { it.startedAtMillis }) else n }
    }
    val base = NotebooksState(notebooks = notebooks, nowMillis = now, lastNotebookId = null, recording = recording, working = working)
    val state = NotebooksStateC2(
        notebooks = base,
        unfiled = unfiled,
        guess = guessNotebook(notebooks, now),
        suggested = unfiled.mapNotNull { l -> guessNotebook(notebooks, l.startedAtMillis)?.let { l.id to it } }.toMap(),
    )
    val actions = NotebooksActionsC2(
        notebooks = NotebooksActions(
            openLecture = { _, id -> toast("Lab: the lecture would open ($id)") },
            backToRecording = {
                recording?.let { r ->
                    val lecture = LectureSummary("new-${r.elapsedMillis}", r.lectureTitle, now, r.elapsedMillis, 0, refined = false)
                    if (r.notebookId == null) unfiled = listOf(lecture) + unfiled else file(lecture, r.notebookId)
                    working = working + (lecture.id to 0f)
                    toast("Lab: stopped; the lecture is listed and its speaker pass runs")
                }
                recording = null
            },
            newNotebook = { t, l -> notebooks = notebooks + Notebook("nb-${notebooks.size + 1}", t.trim(), l, emptyList()) },
            settings = { toast("Lab: Settings would open") },
        ),
        record = { id ->
            if (recording == null) {
                recording = RecordingNow(id, title(now), 0)
                toast("Lab: the lecture screen would open. Back here, tap the banner to stop it.")
            }
        },
        assign = { lectureId, notebookId -> take(lectureId)?.let { file(it, notebookId) } },
        assignToNew = { lectureId, t, l ->
            val id = "nb-${notebooks.size + 1}"
            notebooks = notebooks + Notebook(id, t.trim(), l, emptyList())
            take(lectureId)?.let { file(it, id) }
        },
    )
    return state to actions
}
