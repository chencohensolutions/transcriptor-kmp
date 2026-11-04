package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.Languages
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.notebook.NotebooksState
import com.chencohensolution.transcriptor.notebook.RecordingNow
import com.chencohensolution.transcriptor.notebook.UNFILED
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotebooksActions(
    /** Opens a recorded lecture ([UNFILED] for one not in a notebook). */
    val openLecture: (notebookId: String, lectureId: String) -> Unit = { _, _ -> },
    /** Starts a lecture in a notebook, or in none (null) in [language], to file later. */
    val record: (notebookId: String?, language: String) -> Unit = { _, _ -> },
    val backToRecording: () -> Unit = {},
    val newNotebook: (title: String, language: String) -> Unit = { _, _ -> },
    /** Files an unfiled lecture into a notebook. */
    val assign: (lectureId: String, notebookId: String) -> Unit = { _, _ -> },
    /** Makes a notebook and files an unfiled lecture into it. */
    val assignToNew: (lectureId: String, title: String, language: String) -> Unit = { _, _, _ -> },
    val settings: () -> Unit = {},
    /** Opens the system's folder picker for the lectures folder. */
    val chooseFolder: () -> Unit = {},
    /** "Not now" on the question where lectures are kept. */
    val keepInApp: () -> Unit = {},
)

/** What the Notebooks screen says about the lectures folder, on top of the list. */
sealed interface FolderNotice {
    /** None chosen yet: where should lectures be kept? */
    data object Ask : FolderNotice
    data class Moving(val name: String, val progress: Float) : FolderNotice
    /** The folder can't be reached any more. */
    data class Lost(val name: String) : FolderNotice
}

/**
 * The app's first screen, lectures first: every lecture newest first, grouped today / this week / earlier, each with
 * its notebook's color and name; chips narrow the list to a notebook or to the lectures not filed yet. Lectures
 * recorded without a notebook wait on top ("Not in a notebook yet") with the notebook their time suggests, one tap to
 * move them. At the bottom, the Record bar: it records in no notebook unless the lecture history guesses one for this
 * hour ("Usually on Monday at 10:00") or the list is narrowed to a notebook; its chip picks another, none, or a new
 * one, and the language of a lecture in no notebook. A lecture recording now replaces the bar with a way back to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebooksScreen(state: NotebooksState, actions: NotebooksActions, folder: FolderNotice? = null) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    // The notebook picked for the next lecture: a notebook's id, NONE for none, null to follow the filter or the guess.
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable(state.unfiledLanguage) { mutableStateOf(state.unfiledLanguage) }
    var assigning by remember { mutableStateOf<LectureSummary?>(null) }
    var creatingFor by remember { mutableStateOf<LectureSummary?>(null) }
    val languages = notebookLanguages(state.notebooks.map { it.language } + state.unfiledLanguage)
    if (creating) NewNotebookDialog(languages, onDismiss = { creating = false }) { t, l -> actions.newNotebook(t, l); creating = false }
    creatingFor?.let { lecture ->
        NewNotebookDialog(languages, onDismiss = { creatingFor = null }) { t, l -> actions.assignToNew(lecture.id, t, l); creatingFor = null; assigning = null }
    }
    assigning?.let { lecture ->
        AssignDialog(lecture, state.notebooks, state.suggested[lecture.id]?.notebookId, onDismiss = { assigning = null }, onNew = { creatingFor = lecture }) {
            actions.assign(lecture.id, it); assigning = null
        }
    }
    if (choosingLanguage) {
        LanguageDialog(language, languages, onDismiss = { choosingLanguage = false }) { language = it; target = NONE; choosingLanguage = false }
    }

    val recordIn = state.notebooks.firstOrNull { it.id == (target ?: filter?.let { f -> if (f == UNFILED) NONE else f } ?: state.guess?.notebookId) }
    val guessed = target == null && filter == null && recordIn != null && recordIn.id == state.guess?.notebookId
    val lectures = if (filter == UNFILED) emptyMap() else state.notebooks.filter { filter == null || it.id == filter }
        .flatMap { n -> n.lectures.map { n to it } }
        .sortedByDescending { it.second.startedAtMillis }
        .groupBy { (_, l) ->
            val days = state.nowMillis / DAY - l.startedAtMillis / DAY
            when {
                days == 0L -> R.string.notebooks_today
                days < 7 -> R.string.notebooks_this_week
                else -> R.string.notebooks_earlier
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { IconButton(onClick = actions.settings) { Icon(Icons.Filled.Settings, stringResource(R.string.notebooks_settings)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                val r = state.recording
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    if (r != null) {
                        RecordingBanner(r, state.notebooks.firstOrNull { it.id == r.notebookId }, actions.backToRecording)
                    } else {
                        Column {
                            Box {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.notebooks_records_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    AssistChip(
                                        onClick = { choosing = true },
                                        label = {
                                            Text(
                                                recordIn?.title ?: stringResource(R.string.notebooks_no_notebook_in, languageName(language)),
                                                style = MaterialTheme.typography.labelLarge.copy(textDirection = recordIn?.let { textDirectionOf(it.language) } ?: TextDirection.Content),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        leadingIcon = {
                                            if (recordIn != null) Box(Modifier.size(8.dp).background(notebookColor(recordIn.color), CircleShape))
                                            else Icon(Icons.Filled.Inbox, null, Modifier.size(16.dp))
                                        },
                                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                                    state.notebooks.sortedByDescending { it.id == state.guess?.notebookId }.forEach { n ->
                                        DropdownMenuItem(
                                            text = { Text(n.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirectionOf(n.language))) },
                                            leadingIcon = { Box(Modifier.size(10.dp).background(notebookColor(n.color), CircleShape)) },
                                            trailingIcon = if (n.id == state.guess?.notebookId) ({ Icon(Icons.Filled.AutoAwesome, stringResource(R.string.notebooks_suggested), tint = MaterialTheme.colorScheme.primary) }) else null,
                                            onClick = { target = n.id; choosing = false },
                                        )
                                    }
                                    if (state.notebooks.isNotEmpty()) HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notebooks_no_notebook_assign)) },
                                        leadingIcon = { Icon(Icons.Filled.Inbox, null) },
                                        onClick = { target = NONE; choosing = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notebooks_no_notebook_language, languageName(language))) },
                                        leadingIcon = { Icon(Icons.Filled.Language, null) },
                                        onClick = { choosing = false; choosingLanguage = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notebooks_new_notebook)) },
                                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                                        onClick = { choosing = false; creating = true },
                                    )
                                }
                            }
                            val why = when {
                                guessed -> state.guess?.let { stringResource(R.string.notebooks_usually, weekdayAndTime(it.usualStartMillis)) }
                                recordIn == null -> stringResource(R.string.notebooks_assign_after)
                                else -> null
                            }
                            Row(Modifier.padding(top = 4.dp, bottom = 8.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (why != null) {
                                    if (guessed) Icon(Icons.Filled.AutoAwesome, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(why, style = MaterialTheme.typography.bodySmall, color = if (guessed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                            Button(
                                onClick = { actions.record(recordIn?.id, recordIn?.language ?: language) }, shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Icon(Icons.Filled.Mic, null)
                                Text(stringResource(R.string.notebooks_record), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            folder?.let { notice -> item(key = "folder") { FolderCard(notice, actions) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    item {
                        FilterChip(selected = filter == null, onClick = { filter = null; target = null }, label = { Text(stringResource(R.string.notebooks_all)) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    if (state.unfiled.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = filter == UNFILED, onClick = { filter = if (filter == UNFILED) null else UNFILED; target = null },
                                label = { Text(stringResource(R.string.notebooks_unfiled_chip, state.unfiled.size)) },
                                leadingIcon = { Icon(Icons.Filled.Inbox, null, Modifier.size(16.dp)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    items(state.notebooks, key = { it.id }) { n ->
                        FilterChip(
                            selected = filter == n.id, onClick = { filter = if (filter == n.id) null else n.id; target = null },
                            label = { Text(n.title, style = MaterialTheme.typography.labelLarge.copy(textDirection = textDirectionOf(n.language)), maxLines = 1) },
                            leadingIcon = { Box(Modifier.size(8.dp).background(notebookColor(n.color), CircleShape)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    item {
                        OutlinedButton(onClick = { creating = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Filled.Add, stringResource(R.string.notebooks_new_notebook))
                        }
                    }
                }
            }
            if (state.unfiled.isNotEmpty() && (filter == null || filter == UNFILED)) {
                item(key = UNFILED) { GroupTitle(stringResource(R.string.notebooks_unfiled)) }
                items(state.unfiled, key = { "$UNFILED/" + it.id }) { lecture ->
                    val suggestion = state.suggested[lecture.id]?.let { g -> state.notebooks.firstOrNull { it.id == g.notebookId } }
                    UnfiledRow(
                        lecture, state, suggestion,
                        onOpen = { actions.openLecture(UNFILED, lecture.id) },
                        onMove = { suggestion?.let { actions.assign(lecture.id, it.id) } },
                        onAssign = { assigning = lecture },
                    )
                }
            }
            if (state.notebooks.isEmpty() && state.unfiled.isEmpty()) {
                item { Empty() }
            }
            lectures.forEach { (group, rows) ->
                item(key = group) { GroupTitle(stringResource(group)) }
                items(rows, key = { (n, l) -> n.id + "/" + l.id }) { (notebook, lecture) ->
                    LectureRow(lecture, notebook, state, onOpen = { actions.openLecture(notebook.id, lecture.id) }, showNotebook = filter == null)
                }
            }
        }
    }
}

private const val NONE = ""
private const val DAY = 24 * 60 * 60_000L

/**
 * The languages a notebook, or a lecture in none, can be in, most likely first: the phone's, those in [used], those with
 * models of their own (Hebrew's), then every other language Whisper knows.
 */
@Composable
internal fun notebookLanguages(used: List<String> = emptyList()): List<String> {
    val locales = LocalConfiguration.current.locales
    return Languages.ordered((0 until locales.size()).map { locales[it].language }, used)
}

/** A notebook's color, from the palette by its [index]. */
@Composable
internal fun notebookColor(index: Int): Color = LocalTranscriptorColors.current.speakers.let { it[index % it.size] }

/** A language's name in the phone's language ("עברית", "English"). */
@Composable
internal fun languageName(tag: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return Locale.forLanguageTag(tag).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/** "Monday at 10:00", in the phone's time zone. */
@Composable
private fun weekdayAndTime(millis: Long): String = stringResource(
    R.string.notebooks_slot,
    SimpleDateFormat("EEEE", LocalConfiguration.current.locales[0]).format(Date(millis)),
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis)),
)

/** "Today", "Yesterday", or the date. */
@Composable
private fun lectureDay(millis: Long, now: Long): String = when (now / DAY - millis / DAY) {
    0L -> stringResource(R.string.notebooks_today)
    1L -> stringResource(R.string.notebooks_yesterday)
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}

/** "47 min", "1 h", "1 h 28 min". */
@Composable
private fun lectureLength(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    return when {
        minutes < 60 -> stringResource(R.string.notebooks_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.notebooks_hours, minutes / 60)
        else -> stringResource(R.string.notebooks_hours_minutes, minutes / 60, minutes % 60)
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** The pass running on a lecture, if one is: its progress. */
@Composable
private fun Working(progress: Float?) {
    if (progress == null) return
    Text(stringResource(R.string.notebooks_improving, (progress * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
}

/** A lecture in a list: its notebook (when the list mixes them), its title in the notebook's direction, its facts. */
@Composable
private fun LectureRow(lecture: LectureSummary, notebook: Notebook, state: NotebooksState, onOpen: () -> Unit, showNotebook: Boolean) {
    val color = notebookColor(notebook.color)
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showNotebook) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 6.dp).size(8.dp).background(color, CircleShape))
                Text(
                    notebook.title, style = MaterialTheme.typography.labelMedium.copy(textDirection = textDirectionOf(notebook.language)),
                    color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            lecture.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = titleDirection(notebook.language)),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(
                lectureDay(lecture.startedAtMillis, state.nowMillis), lectureLength(lecture.durationMillis),
                if (lecture.noteCount > 0) stringResource(R.string.notebooks_notes, lecture.noteCount) else null,
                if (lecture.refined) stringResource(R.string.notebooks_refined) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        )
        Working(state.working[lecture.id])
    }
}

/** A lecture not in a notebook: its weekday and time, and Move to the suggested notebook (or Put in a notebook). */
@Composable
private fun UnfiledRow(lecture: LectureSummary, state: NotebooksState, suggestion: Notebook?, onOpen: () -> Unit, onMove: () -> Unit, onAssign: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(lecture.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Its title is its date: the weekday and time say more, and are what the suggestion goes by.
        Text(
            listOfNotNull(
                weekdayAndTime(lecture.startedAtMillis), lectureLength(lecture.durationMillis),
                if (lecture.noteCount > 0) stringResource(R.string.notebooks_notes, lecture.noteCount) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        )
        Working(state.working[lecture.id])
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (suggestion != null) {
                FilledTonalButton(onClick = onMove, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Box(Modifier.padding(end = 6.dp).size(8.dp).background(notebookColor(suggestion.color), CircleShape))
                    Text(
                        stringResource(R.string.notebooks_move_to, isolated(suggestion.title)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                TextButton(onClick = onAssign) { Text(stringResource(R.string.notebooks_other)) }
            } else {
                OutlinedButton(onClick = onAssign) { Text(stringResource(R.string.notebooks_assign)) }
            }
        }
    }
}

/** A lecture is recording: its notebook (or none yet), its clock, and the way back to it. */
@Composable
private fun RecordingBanner(recording: RecordingNow, notebook: Notebook?, onBack: () -> Unit) {
    val red = LocalTranscriptorColors.current.recording
    Surface(color = red.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onBack)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(red, CircleShape))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.notebooks_recording, formatClock(recording.elapsedMillis)), fontWeight = FontWeight.SemiBold, color = red)
                Text(
                    notebook?.title ?: stringResource(R.string.notebooks_no_notebook_assign),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = notebook?.let { textDirectionOf(it.language) } ?: TextDirection.Content),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = red, contentColor = Color.White)) {
                Text(stringResource(R.string.notebooks_back_to_lecture))
            }
        }
    }
}

/** No notebooks or lectures yet: what a notebook is. Record works without one. */
@Composable
private fun Empty() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.notebooks_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.notebooks_empty_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Which notebook an unfiled lecture goes in: the suggested one first, or a new one. */
@Composable
private fun AssignDialog(
    lecture: LectureSummary, notebooks: List<Notebook>, suggested: String?,
    onDismiss: () -> Unit, onNew: () -> Unit, onAssign: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_assign_title, isolated(lecture.title))) },
        text = {
            Column {
                notebooks.sortedByDescending { it.id == suggested }.forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable { onAssign(n.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(end = 12.dp).size(10.dp).background(notebookColor(n.color), CircleShape))
                        Text(
                            n.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirectionOf(n.language)),
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (n.id == suggested) Text(stringResource(R.string.notebooks_suggested), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                if (notebooks.isNotEmpty()) HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable(onClick = onNew).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null)
                    Text(stringResource(R.string.notebooks_new_notebook), Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** The languages, as radio buttons. */
@Composable
private fun LanguageChoices(language: String, languages: List<String>, onChoose: (String) -> Unit) {
    // A hundred languages: a list that scrolls in the dialog.
    LazyColumn(Modifier.selectableGroup().heightIn(max = 360.dp)) {
        items(languages) { tag ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = language == tag, role = Role.RadioButton) { onChoose(tag) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = language == tag, onClick = null)
                Text(languageName(tag), Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** The language of a lecture recorded in no notebook (it picks the live model). */
@Composable
private fun LanguageDialog(language: String, languages: List<String>, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_language_title)) },
        text = { LanguageChoices(language, languages, onChoose) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** A new notebook: its name and language (which picks the speech models). */
@Composable
private fun NewNotebookDialog(languages: List<String>, onDismiss: () -> Unit, onCreate: (title: String, language: String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(languages.first()) }
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
                Text(stringResource(R.string.notebooks_new_language), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Text(stringResource(R.string.notebooks_new_language_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A menu, not radio buttons: the dialog stays short enough for Create to show above the keyboard.
                var open by remember { mutableStateOf(false) }
                Box(Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { open = true }) {
                        Text(languageName(language))
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        languages.forEach { tag ->
                            DropdownMenuItem(text = { Text(languageName(tag)) }, onClick = { language = tag; open = false })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title, language) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.notebooks_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** The lectures folder: the question where to keep lectures, the move into the folder, or the folder lost. */
@Composable
private fun FolderCard(notice: FolderNotice, actions: NotebooksActions) {
    val lost = notice is FolderNotice.Lost
    Surface(
        color = if (lost) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (notice) {
                FolderNotice.Ask -> {
                    Text(stringResource(R.string.folder_ask_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.folder_ask_note), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.chooseFolder) { Text(stringResource(R.string.folder_choose)) }
                        TextButton(onClick = actions.keepInApp) { Text(stringResource(R.string.folder_not_now)) }
                    }
                }
                is FolderNotice.Moving -> {
                    Text(stringResource(R.string.folder_moving, notice.name, (notice.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { notice.progress }, modifier = Modifier.fillMaxWidth())
                }
                is FolderNotice.Lost -> {
                    Text(stringResource(R.string.folder_lost, notice.name), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions.chooseFolder) { Text(stringResource(R.string.folder_choose_again)) }
                }
            }
        }
    }
}
