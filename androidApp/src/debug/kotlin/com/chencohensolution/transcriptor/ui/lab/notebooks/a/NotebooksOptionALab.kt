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
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// Fakes: labels and moments' labels (the shared logic has notebooks): LabData's notebooks become labels, plus an
// "Exam" label on two moments so some have two. The label guess (the logic guesses one notebook): on Monday before the
// psychology class it is Psychology; on Tuesday evening none (a-no-guess). The suggestion for a moment without labels
// (Saturday 10:05 → Statistics). Recording is a clock; labels, renames and deletes are in memory; a speaker pass runs on
// a moment just stopped. Opening a moment and Settings only say so. Can't show: labels on the moment's own screens.
internal val notebooksOptionA = LabOption("a", "Labels, before or after: chips on top, labels on the Record bar", "Chips filter by label; Record has the guessed labels on and a language chip; hold to select, then set labels in a sheet") {
    val (state, actions) = rememberNotebooksLabA(now = MONDAY_BEFORE_CLASS_A)
    NotebooksScreenA(state, actions)
}

internal val notebooksOptionANoGuess = LabOption("a-no-guess", "Labels, before or after: no class now", "Option a on Tuesday evening: no label guessed, so Record has none") {
    val (state, actions) = rememberNotebooksLabA(now = TUESDAY_EVENING_A)
    NotebooksScreenA(state, actions)
}

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN
private val LATEST = LabData.notebooks.first().lectures.first().startedAtMillis
private val MONDAY_BEFORE_CLASS_A = LATEST + 7 * DAY - 10 * MIN
private val TUESDAY_EVENING_A = LATEST + 8 * DAY + 8 * 60 * MIN

private val SHORT_NAMES = mapOf("psych" to "פסיכולוגיה", "stats" to "סטטיסטיקה", "english" to "Academic English")

@Composable
private fun rememberNotebooksLabA(now: Long): Pair<NotebooksOptionAState, NotebooksOptionAActions> {
    val context = LocalContext.current
    fun title(at: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
    var labels by remember {
        mutableStateOf(LabData.notebooks.map { NotebooksOptionALabel(it.id, SHORT_NAMES[it.id] ?: it.title, it.color) } + NotebooksOptionALabel("exam", "מבחן", 3))
    }
    var moments by remember {
        val filed = LabData.notebooks.flatMap { n ->
            n.lectures.map { l -> NotebooksOptionAMoment(l, n.language, listOf(n.id) + if (l.id in setOf("lecture-6", "stats-3")) listOf("exam") else emptyList()) }
        }
        val unlabeled = listOf(
            LectureSummary("unfiled-sat", title(LATEST + 5 * DAY + 5 * MIN), LATEST + 5 * DAY + 5 * MIN, 88 * MIN, 4, refined = true),
            LectureSummary("unfiled-thu", title(LATEST + 3 * DAY + 510 * MIN), LATEST + 3 * DAY + 510 * MIN, 52 * MIN, 0, refined = true),
        ).map { NotebooksOptionAMoment(it, "he", emptyList()) }
        mutableStateOf((filed + unlabeled).sortedByDescending { it.summary.startedAtMillis })
    }
    var recording by remember { mutableStateOf<NotebooksOptionARecording?>(null) }
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
    val guess = if (now == MONDAY_BEFORE_CLASS_A) NotebooksOptionAGuess(listOf("psych"), LATEST) else null
    val suggested = moments.filter { it.labelIds.isEmpty() && it.summary.id == "unfiled-sat" }.associate { it.summary.id to "stats" }
    val state = NotebooksOptionAState(
        labels = labels, moments = moments, nowMillis = now,
        language = if (guess != null) "he" else moments.first().language,
        guess = guess, suggested = suggested, recording = recording, working = working,
    )
    val actions = NotebooksOptionAActions(
        openMoment = { toast("Lab: the moment would open ($it)") },
        record = { ids, language ->
            if (recording == null) {
                recording = NotebooksOptionARecording(ids, title(now), 0)
                recordingLanguage = language
                toast("Lab: the moment screen would open ($language). Back here, tap the banner to stop it.")
            }
        },
        backToRecording = {
            recording?.let { r ->
                val m = NotebooksOptionAMoment(LectureSummary("new-${r.elapsedMillis}", r.title, now, r.elapsedMillis, 0, refined = false), recordingLanguage, r.labelIds)
                moments = listOf(m) + moments
                working = working + (m.summary.id to 0f)
                toast("Lab: stopped; the moment is listed and its speaker pass runs")
            }
            recording = null
        },
        newLabel = { name ->
            val id = "label-${labels.size + 1}"
            labels = labels + NotebooksOptionALabel(id, name, labels.size)
            id
        },
        setLabels = { ids, add, remove ->
            moments = moments.map { m -> if (m.summary.id in ids) m.copy(labelIds = (m.labelIds + add).distinct() - remove) else m }
        },
        renameLabel = { id, name -> labels = labels.map { if (it.id == id) it.copy(name = name) else it } },
        deleteLabel = { id ->
            labels = labels.filter { it.id != id }
            moments = moments.map { it.copy(labelIds = it.labelIds - id) }
        },
        settings = { toast("Lab: Settings would open") },
        delete = { ids -> moments = moments.filter { it.summary.id !in ids }; toast("Lab: deleted (the app deletes the moment's folder)") },
    )
    return state to actions
}
