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
import com.chencohensolution.transcriptor.notebook.Label
import com.chencohensolution.transcriptor.notebook.Library
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.NotebooksState
import com.chencohensolution.transcriptor.notebook.RecordingNow
import com.chencohensolution.transcriptor.notebook.notebooksState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

// Fakes: the labels and moments (LabData) and the time: the Monday after its latest moment at 9:50, ten minutes before
// the psychology class (current), or Tuesday at 18:00, when no class usually is (current-no-guess). Two moments
// without labels: Saturday 10:05 (statistics' time, so suggested there) and Thursday 18:30 (no suggestion). Recording
// is a clock (the banner stops it and lists the moment with the labels it was started with); labels, renames and
// deletes are in memory; a speaker pass runs on a moment just stopped. The guess and suggestions are the app's
// notebooksState on that data, in the phone's time zone. Opening a moment and Settings only say so.
internal val notebooksCurrent = LabOption("current", "Labels: chips on top, labels on the Record bar (the app's)", "NotebooksScreen with lab labels, two moments without labels, Monday before class") {
    val (state, actions) = rememberNotebooksLab(now = MONDAY_BEFORE_CLASS)
    NotebooksScreen(state, actions)
}

internal val notebooksCurrentNoGuess = LabOption("current-no-guess", "Labels: no class now", "The app's screen on Tuesday evening: no label guessed, so Record has none") {
    val (state, actions) = rememberNotebooksLab(now = TUESDAY_EVENING)
    NotebooksScreen(state, actions)
}

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN
private val LATEST = LabData.moments.first().startedAtMillis
private val MONDAY_BEFORE_CLASS = LATEST + 7 * DAY - 10 * MIN
private val TUESDAY_EVENING = LATEST + 8 * DAY + 8 * 60 * MIN

@Composable
private fun rememberNotebooksLab(now: Long): Pair<NotebooksState, NotebooksActions> {
    val context = LocalContext.current
    fun title(at: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
    var labels by remember { mutableStateOf(LabData.labels) }
    var moments by remember {
        val unlabeled = listOf(
            LectureSummary("unlabeled-sat", title(LATEST + 5 * DAY + 5 * MIN), LATEST + 5 * DAY + 5 * MIN, 88 * MIN, 4, refined = true, language = "he"),
            LectureSummary("unlabeled-thu", title(LATEST + 3 * DAY + 510 * MIN), LATEST + 3 * DAY + 510 * MIN, 52 * MIN, 0, refined = true, language = "he"),
        )
        mutableStateOf((LabData.moments + unlabeled).sortedByDescending { it.startedAtMillis })
    }
    var recording by remember { mutableStateOf<RecordingNow?>(null) }
    var recordingLanguage by remember { mutableStateOf("he") }
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
            working = working.mapValues { it.value + 0.02f }.filterValues { it < 1f }
        }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val zone = TimeZone.getDefault()
    val state = notebooksState(Library(labels, moments, "he"), now, { zone.getOffset(it).toLong() }, recording, working)
    val actions = NotebooksActions(
        openLecture = { toast("Lab: the moment would open ($it)") },
        record = { ids, language ->
            if (recording == null) {
                recording = RecordingNow(ids, title(now), 0)
                recordingLanguage = language
                toast("Lab: the moment screen would open ($language). Back here, tap the banner to stop it.")
            }
        },
        backToRecording = {
            recording?.let { r ->
                val m = LectureSummary("new-${r.elapsedMillis}", r.lectureTitle, now, r.elapsedMillis, 0, refined = false, language = recordingLanguage, labels = r.labels)
                moments = listOf(m) + moments
                working = working + (m.id to 0f)
                toast("Lab: stopped; the moment is listed and its speaker pass runs")
            }
            recording = null
        },
        newLabel = { name ->
            val id = "label-${labels.size + 1}"
            labels = labels + Label(id, name, labels.size)
            id
        },
        setLabels = { ids, add, remove ->
            moments = moments.map { m -> if (m.id in ids && m.id !in working) m.copy(labels = (m.labels + add).distinct() - remove) else m }
        },
        renameLabel = { id, name -> labels = labels.map { if (it.id == id) it.copy(name = name) else it } },
        deleteLabel = { id ->
            labels = labels.filter { it.id != id }
            moments = moments.map { it.copy(labels = it.labels - id) }
        },
        settings = { toast("Lab: Settings would open") },
        delete = { ids -> moments = moments.filter { it.id !in ids }; toast("Lab: deleted (the app deletes the moment's folder)") },
    )
    return state to actions
}
