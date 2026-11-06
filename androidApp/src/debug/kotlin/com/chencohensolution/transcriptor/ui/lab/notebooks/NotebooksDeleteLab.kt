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
import com.chencohensolution.transcriptor.notebook.Library
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.notebook.NotebooksState
import com.chencohensolution.transcriptor.notebook.RecordingNow
import com.chencohensolution.transcriptor.notebook.notebooksState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/**
 * The Notebooks lab (as `notebooks current`, on the Monday before class) with deleting: for the round's delete options
 * (lab only, deleted when the round closes). Deleting takes the lectures out of memory; nothing is on disk.
 */
@Composable
internal fun rememberNotebooksDeleteLab(): Triple<NotebooksState, NotebooksActions, (List<String>) -> Unit> {
    val (state, actions, delete) = rememberNotebooksLab(now = MONDAY_BEFORE_CLASS)
    return Triple(state, actions, delete)
}

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN
private val LATEST = LabData.notebooks.first().lectures.first().startedAtMillis
private val MONDAY_BEFORE_CLASS = LATEST + 7 * DAY - 10 * MIN
private val TUESDAY_EVENING = LATEST + 8 * DAY + 8 * 60 * MIN

@Composable
private fun rememberNotebooksLab(now: Long): Triple<NotebooksState, NotebooksActions, (List<String>) -> Unit> {
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
    fun create(title: String, language: String) = Notebook("nb-${notebooks.size + 1}", title.trim(), language, emptyList(), notebooks.size).also { notebooks = notebooks + it }
    val zone = TimeZone.getDefault()
    val state = notebooksState(Library(notebooks, unfiled, "he"), now, { zone.getOffset(it).toLong() }, recording, working)
    val actions = NotebooksActions(
        openLecture = { _, id -> toast("Lab: the lecture would open ($id)") },
        record = { id, language ->
            if (recording == null) {
                recording = RecordingNow(id, title(now), 0)
                toast("Lab: the lecture screen would open ($language). Back here, tap the banner to stop it.")
            }
        },
        backToRecording = {
            recording?.let { r ->
                val lecture = LectureSummary("new-${r.elapsedMillis}", r.lectureTitle, now, r.elapsedMillis, 0, refined = false)
                val notebookId = r.notebookId
                if (notebookId == null) unfiled = listOf(lecture) + unfiled else file(lecture, notebookId)
                working = working + (lecture.id to 0f)
                toast("Lab: stopped; the lecture is listed and its speaker pass runs")
            }
            recording = null
        },
        newNotebook = { t, l -> create(t, l) },
        assign = { lectureId, notebookId -> take(lectureId)?.let { file(it, notebookId) } },
        assignToNew = { lectureId, t, l -> val n = create(t, l); take(lectureId)?.let { file(it, n.id) } },
        settings = { toast("Lab: Settings would open") },
    )
    val delete: (List<String>) -> Unit = { ids ->
        notebooks = notebooks.map { n -> n.copy(lectures = n.lectures.filter { it.id !in ids }) }
        unfiled = unfiled.filter { it.id !in ids }
        toast(if (ids.size == 1) "Lab: deleted (its folder would go from the lectures folder)" else "Lab: ${ids.size} deleted (their folders would go from the lectures folder)")
    }
    return Triple(state, actions, delete)
}
