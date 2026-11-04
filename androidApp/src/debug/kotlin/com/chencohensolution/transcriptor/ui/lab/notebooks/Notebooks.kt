package com.chencohensolution.transcriptor.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.lab.LabData
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Notebook
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Shared by the Notebooks options: the state as a controller would produce it, the actions, the lab's fakes and the
// small pieces every option draws the same way (dates, lengths, the new notebook dialog).

/** What the Notebooks screen shows. */
data class NotebooksState(
    /** The courses, the last one recorded in first; each with its lectures, newest first. */
    val notebooks: List<Notebook>,
    val nowMillis: Long,
    /** The notebook a new lecture goes into unless another is picked: the last one recorded in. */
    val lastNotebookId: String?,
    /** A lecture recording now (the app was left from its screen). */
    val recording: RecordingNow? = null,
    /** Lectures with a pass running after them (the speaker pass, refining, compressing to FLAC): their progress. */
    val working: Map<String, Float> = emptyMap(),
)

data class RecordingNow(val notebookId: String, val lectureTitle: String, val elapsedMillis: Long)

class NotebooksActions(
    val openLecture: (notebookId: String, lectureId: String) -> Unit = { _, _ -> },
    /** Starts a lecture in a notebook (the microphone's permission first). */
    val record: (notebookId: String) -> Unit = {},
    val backToRecording: () -> Unit = {},
    val newNotebook: (title: String, language: String) -> Unit = { _, _ -> },
    val settings: () -> Unit = {},
)

/**
 * Fakes for the lab: the notebooks of [LabData] (or none), the time (an hour after the latest lecture started), a
 * refine pass running on the latest lecture, then done. Record starts a clock in that notebook; going back to it stops
 * the recording and lists the lecture. Opening a lecture and Settings only say what would open.
 */
@Composable
internal fun rememberNotebooksLab(empty: Boolean = false): Pair<NotebooksState, NotebooksActions> {
    val context = LocalContext.current
    val now = remember { LabData.notebooks.first().lectures.first().startedAtMillis + 60 * 60_000L }
    var notebooks by remember { mutableStateOf(if (empty) emptyList() else LabData.notebooks) }
    var last by remember { mutableStateOf(notebooks.firstOrNull()?.id) }
    var recording by remember { mutableStateOf<RecordingNow?>(null) }
    var working by remember { mutableStateOf(if (empty) emptyMap() else mapOf("lecture-7" to 0.3f)) }
    LaunchedEffect(recording != null) {
        while (recording != null) {
            delay(1_000)
            recording = recording?.let { it.copy(elapsedMillis = it.elapsedMillis + 1_000) }
        }
    }
    LaunchedEffect(working.isNotEmpty()) {
        while (working.isNotEmpty()) {
            delay(150)
            val done = working.filterValues { it >= 1f }.keys
            notebooks = notebooks.map { n -> n.copy(lectures = n.lectures.map { if (it.id in done) it.copy(refined = true) else it }) }
            working = working.filterKeys { it !in done }.mapValues { (it.value + 0.01f).coerceAtMost(1f) }
        }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val state = NotebooksState(
        notebooks = notebooks.sortedByDescending { it.id == last },
        nowMillis = now,
        lastNotebookId = last,
        recording = recording,
        working = working,
    )
    val actions = NotebooksActions(
        openLecture = { _, id -> toast("Lab: the lecture would open ($id)") },
        record = { id ->
            if (recording == null) {
                recording = RecordingNow(id, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(now)), 0)
                last = id
                toast("Lab: the lecture screen would open. Back here, tap the banner to stop it.")
            }
        },
        backToRecording = {
            recording?.let { r ->
                val lecture = LectureSummary("new-${r.elapsedMillis}", r.lectureTitle, now, r.elapsedMillis, 0, refined = false)
                notebooks = notebooks.map { if (it.id == r.notebookId) it.copy(lectures = listOf(lecture) + it.lectures) else it }
                working = working + (lecture.id to 0f)
                toast("Lab: stopped; the lecture is listed and its speaker pass runs")
            }
            recording = null
        },
        newNotebook = { title, language ->
            val id = "nb-${notebooks.size + 1}"
            notebooks = notebooks + Notebook(id, title.trim(), language, emptyList())
            last = id
        },
        settings = { toast("Lab: Settings would open") },
    )
    return state to actions
}

/** The languages a new notebook can be in: Hebrew has its own models, the others the multilingual ones. */
internal val notebookLanguages = listOf("he", "en", "ar", "ru", "fr", "es", "de")

/** A language's name in the phone's language ("עברית", "English"). */
@Composable
internal fun languageName(tag: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return Locale.forLanguageTag(tag).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/** "Today", "Yesterday", or the date. */
@Composable
internal fun lectureDay(millis: Long, now: Long): String {
    val day = 24 * 60 * 60_000L
    val days = (now / day) - (millis / day)
    return when (days) {
        0L -> stringResource(R.string.notebooks_today)
        1L -> stringResource(R.string.notebooks_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}

/** "47 min", "1 h", "1 h 28 min". */
@Composable
internal fun lectureLength(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    return when {
        minutes < 60 -> stringResource(R.string.notebooks_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.notebooks_hours, minutes / 60)
        else -> stringResource(R.string.notebooks_hours_minutes, minutes / 60, minutes % 60)
    }
}

/** A lecture's facts in one line: its day, length, notes, and whether its transcript was refined. */
@Composable
internal fun lectureFacts(lecture: LectureSummary, now: Long): String = listOfNotNull(
    lectureDay(lecture.startedAtMillis, now),
    lectureLength(lecture.durationMillis),
    if (lecture.noteCount > 0) stringResource(R.string.notebooks_notes, lecture.noteCount) else null,
    if (lecture.refined) stringResource(R.string.notebooks_refined) else null,
).joinToString(" · ")

/** A new notebook: its name and language (which picks the speech models). */
@Composable
internal fun NotebooksNewDialog(onDismiss: () -> Unit, onCreate: (title: String, language: String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(notebookLanguages.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_new_notebook)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text(stringResource(R.string.notebooks_new_name)) },
                    placeholder = { Text(stringResource(R.string.notebooks_new_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.notebooks_new_language), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Text(stringResource(R.string.notebooks_new_language_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup().padding(top = 4.dp)) {
                    notebookLanguages.forEach { tag ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = language == tag, role = Role.RadioButton) { language = tag }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == tag, onClick = null)
                            Text(languageName(tag), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title, language) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.notebooks_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}
