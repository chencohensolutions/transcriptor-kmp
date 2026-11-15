package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.Languages
import com.chencohensolution.transcriptor.notebook.Label
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.NotebooksState
import com.chencohensolution.transcriptor.notebook.RecordingNow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotebooksActions(
    /** Opens a recorded moment. */
    val openLecture: (lectureId: String) -> Unit = {},
    /** Starts a moment with these labels (maybe none) in [language]. */
    val record: (labels: List<String>, language: String) -> Unit = { _, _ -> },
    val backToRecording: () -> Unit = {},
    /** Makes a label; returns its id. */
    val newLabel: (name: String) -> String = { "" },
    /** Adds [add] and removes [remove] on every moment in [lectureIds]. */
    val setLabels: (lectureIds: List<String>, add: Set<String>, remove: Set<String>) -> Unit = { _, _, _ -> },
    val renameLabel: (labelId: String, name: String) -> Unit = { _, _ -> },
    /** Deletes a label: its moments stay, without it. */
    val deleteLabel: (labelId: String) -> Unit = {},
    val settings: () -> Unit = {},
    /** Opens the system's folder picker for the lectures folder. */
    val chooseFolder: () -> Unit = {},
    /** "Not now" on the question where lectures are kept. */
    val keepInApp: () -> Unit = {},
    /** Deletes moments for good (their folders: recording, transcript, notes), once their Undo is gone. */
    val delete: (lectureIds: List<String>) -> Unit = {},
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
 * The app's first screen (round 3's a): every moment newest first, grouped today / this week / earlier, each with all
 * its labels. The chips on top narrow the list to a label (or to
 * moments without one); moments without labels wait on top with the label their time suggests, one tap to add it. The Record bar has the labels the next moment gets (the
 * guessed ones, ✦, already on) and its language. Holding moments selects them; the label button sets their labels in
 * a sheet with a checkbox per label (some selected moments have it: a dash), where labels are also made, renamed and
 * deleted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotebooksScreen(all: NotebooksState, actions: NotebooksActions, folder: FolderNotice? = null) {
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val state = all.copy(moments = all.moments.filter { it.id !in hidden })
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedOne = stringResource(R.string.notebooks_deleted_one)
    val deletedMany = stringResource(R.string.notebooks_deleted_many)
    val undo = stringResource(R.string.notebooks_undo)
    fun deleteWithUndo(ids: List<String>) {
        hidden = hidden + ids
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(if (ids.size == 1) deletedOne else deletedMany.format(ids.size), actionLabel = undo, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) hidden = hidden - ids.toSet() else actions.delete(ids)
        }
    }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    fun toggle(id: String) {
        if (id in state.working) return
        selected = if (id in selected) selected - id else selected + id
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    // The list's filter: a label's id, NO_LABEL, or null for every moment.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    // The next moment's labels once the user changed them; null follows the filter and the guess.
    var chosen by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var language by rememberSaveable(state.language) { mutableStateOf(state.language) }
    var labeling by remember { mutableStateOf<List<String>?>(null) }
    var choosingLabels by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    val guessed = state.guess?.labels.orEmpty().filter { id -> state.labels.any { it.id == id } }
    val nextLabels = chosen ?: (filter?.takeIf { it != NO_LABEL }?.let { listOf(it) } ?: guessed)
    val labelsById = state.labels.associateBy { it.id }
    val unlabeled = state.moments.filter { it.labels.isEmpty() }
    val shown = when (filter) {
        null -> state.moments.filter { it.labels.isNotEmpty() }
        NO_LABEL -> emptyList()
        else -> state.moments.filter { filter in it.labels }
    }.groupBy { m ->
        val days = state.nowMillis / DAY - m.startedAtMillis / DAY
        when {
            days == 0L -> R.string.notebooks_today
            days < 7 -> R.string.notebooks_this_week
            else -> R.string.notebooks_earlier
        }
    }

    labeling?.let { ids ->
        LabelsSheet(
            labels = state.labels, moments = state.moments.filter { it.id in ids },
            onDismiss = { labeling = null; selected = emptySet() },
            onSet = { add, remove -> actions.setLabels(ids, add, remove) },
            onNew = { name -> actions.newLabel(name).also { actions.setLabels(ids, setOf(it), emptySet()) } },
            onRename = actions.renameLabel, onDelete = actions.deleteLabel,
        )
    }
    if (creating) NewLabelDialog(onDismiss = { creating = false }) { actions.newLabel(it); creating = false }
    if (choosingLanguage) {
        LanguageDialog(language, notebookLanguages(state.moments.map { it.language }), onDismiss = { choosingLanguage = false }) { language = it; choosingLanguage = false }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = { IconButton(onClick = actions.settings) { Icon(Icons.Filled.Settings, stringResource(R.string.notebooks_settings)) } },
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.notebooks_clear_selection)) } },
                    title = { Text(stringResource(R.string.notebooks_selected, selected.size)) },
                    actions = {
                        IconButton(onClick = { labeling = selected.toList() }) { Icon(Icons.AutoMirrored.Outlined.Label, stringResource(R.string.notebooks_labels)) }
                        IconButton(onClick = { deleteWithUndo(selected.toList()); selected = emptySet() }) { Icon(Icons.Filled.Delete, stringResource(R.string.notebooks_delete)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    val r = state.recording
                    if (r != null) {
                        RecordingBanner(r, r.labels.mapNotNull { labelsById[it] }, actions.backToRecording)
                    } else {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    LazyRow(verticalAlignment = Alignment.CenterVertically) {
                                        if (nextLabels.isEmpty()) {
                                            item {
                                                AssistChip(
                                                    onClick = { choosingLabels = true },
                                                    label = { Text(stringResource(R.string.notebooks_no_labels)) },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(16.dp)) },
                                                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                                                )
                                            }
                                        }
                                        items(nextLabels.mapNotNull { labelsById[it] }, key = { it.id }) { l ->
                                            AssistChip(
                                                onClick = { choosingLabels = true },
                                                label = { Text(l.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                leadingIcon = { LabelDot(l.color) },
                                                trailingIcon = if (chosen == null && l.id in guessed) ({ Icon(Icons.Filled.AutoAwesome, stringResource(R.string.notebooks_suggested), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }) else null,
                                                modifier = Modifier.padding(end = 6.dp),
                                            )
                                        }
                                        if (nextLabels.isNotEmpty()) {
                                            item {
                                                IconButton(onClick = { choosingLabels = true }) { Icon(Icons.Filled.Add, stringResource(R.string.notebooks_labels)) }
                                            }
                                        }
                                    }
                                    LabelsMenu(
                                        expanded = choosingLabels, labels = state.labels, checked = nextLabels.toSet(), guessed = guessed.toSet(),
                                        onDismiss = { choosingLabels = false },
                                        onToggle = { id -> chosen = if (id in nextLabels) nextLabels - id else nextLabels + id },
                                        onNew = { choosingLabels = false; creating = true },
                                    )
                                }
                                AssistChip(
                                    onClick = { choosingLanguage = true },
                                    label = { Text(languageName(language), maxLines = 1) },
                                    leadingIcon = { Icon(Icons.Filled.Language, null, Modifier.size(16.dp)) },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            val why = when {
                                chosen == null && filter == null && guessed.isNotEmpty() -> state.guess?.let {
                                    stringResource(R.string.notebooks_usually, isolated(guessed.mapNotNull { id -> labelsById[id]?.name }.joinToString(", ")), weekdayAndTime(it.usualStartMillis))
                                }
                                nextLabels.isEmpty() -> stringResource(R.string.notebooks_label_later)
                                else -> null
                            }
                            Row(Modifier.padding(top = 4.dp, bottom = 8.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (why != null) {
                                    val isGuess = chosen == null && filter == null && guessed.isNotEmpty()
                                    if (isGuess) Icon(Icons.Filled.AutoAwesome, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        why, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (isGuess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                            Button(
                                onClick = { actions.record(nextLabels, language); chosen = null }, shape = RoundedCornerShape(16.dp),
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
                        FilterChip(selected = filter == null, onClick = { filter = null; chosen = null }, label = { Text(stringResource(R.string.notebooks_all)) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    if (unlabeled.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = filter == NO_LABEL, onClick = { filter = if (filter == NO_LABEL) null else NO_LABEL; chosen = null },
                                label = { Text(stringResource(R.string.notebooks_no_label_chip, unlabeled.size)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    items(state.labels, key = { it.id }) { l ->
                        FilterChip(
                            selected = filter == l.id, onClick = { filter = if (filter == l.id) null else l.id; chosen = null },
                            label = { Text(l.name, maxLines = 1) }, leadingIcon = { LabelDot(l.color) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    item {
                        OutlinedButton(onClick = { creating = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Filled.Add, stringResource(R.string.notebooks_new_label))
                        }
                    }
                }
            }
            if (unlabeled.isNotEmpty() && (filter == null || filter == NO_LABEL)) {
                item(key = NO_LABEL) { GroupTitle(stringResource(R.string.notebooks_no_label_title)) }
                items(unlabeled, key = { "$NO_LABEL/" + it.id }) { m ->
                    val suggestion = state.suggested[m.id]?.takeIf { m.id !in state.working }?.let { labelsById[it] }
                    MomentRow(
                        m, emptyList(), state, selected = m.id in selected,
                        onOpen = { if (selected.isEmpty()) actions.openLecture(m.id) else toggle(m.id) },
                        onLongPress = { toggle(m.id) },
                    ) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (suggestion != null) {
                                FilledTonalButton(onClick = { actions.setLabels(listOf(m.id), setOf(suggestion.id), emptySet()) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    LabelDot(suggestion.color)
                                    Text(
                                        stringResource(R.string.notebooks_add, isolated(suggestion.name)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                                    )
                                }
                                TextButton(onClick = { labeling = listOf(m.id) }) { Text(stringResource(R.string.notebooks_labels_more)) }
                            } else {
                                OutlinedButton(onClick = { labeling = listOf(m.id) }) { Text(stringResource(R.string.notebooks_add_labels)) }
                            }
                        }
                    }
                }
            }
            if (state.moments.isEmpty()) item { Empty() }
            shown.forEach { (group, rows) ->
                item(key = group) { GroupTitle(stringResource(group)) }
                items(rows, key = { it.id }) { m ->
                    MomentRow(
                        m, m.labels.filter { it != filter }.mapNotNull { labelsById[it] }, state, selected = m.id in selected,
                        onOpen = { if (selected.isEmpty()) actions.openLecture(m.id) else toggle(m.id) },
                        onLongPress = { toggle(m.id) },
                    )
                }
            }
        }
    }
}

private const val NO_LABEL = "-"
private const val DAY = 24 * 60 * 60_000L

@Composable
private fun LabelDot(color: Int) = Box(Modifier.size(8.dp).background(notebookColor(color), CircleShape))

/** A moment's labels, in their colors: "● Psychology  ● Exam". */

@Composable
private fun LabelPills(labels: List<Label>) {
    if (labels.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelDot(l.color)
                Text(l.name, style = MaterialTheme.typography.labelMedium, color = notebookColor(l.color), maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentRow(
    m: LectureSummary, labels: List<Label>, state: NotebooksState, selected: Boolean,
    onOpen: () -> Unit, onLongPress: () -> Unit, extra: @Composable () -> Unit = {},
) {
    val l = m
    Column(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LabelPills(labels)
        Text(l.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = titleDirection(m.language)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                if (m.labels.isEmpty()) weekdayAndTime(l.startedAtMillis) else momentDay(l.startedAtMillis, state.nowMillis),
                momentLength(l.durationMillis),
                if (l.noteCount > 0) stringResource(R.string.notebooks_notes, l.noteCount) else null,
                if (l.refined) stringResource(R.string.notebooks_refined) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        )
        state.working[l.id]?.let { p ->
            Text(stringResource(R.string.notebooks_improving, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        extra()
    }
}

/** The labels for the next moment, as a menu of checkboxes: the guessed ones marked ✦, then New label. */
@Composable
private fun LabelsMenu(
    expanded: Boolean, labels: List<Label>, checked: Set<String>, guessed: Set<String>,
    onDismiss: () -> Unit, onToggle: (String) -> Unit, onNew: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        labels.sortedByDescending { it.id in guessed }.forEach { l ->
            DropdownMenuItem(
                text = { Text(l.name) },
                leadingIcon = { TriStateCheckbox(state = if (l.id in checked) ToggleableState.On else ToggleableState.Off, onClick = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (l.id in guessed) Icon(Icons.Filled.AutoAwesome, stringResource(R.string.notebooks_suggested), Modifier.padding(end = 8.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        LabelDot(l.color)
                    }
                },
                onClick = { onToggle(l.id) },
            )
        }
        if (labels.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.notebooks_new_label)) }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = onNew)
    }
}

/**
 * The labels of [moments]: a checkbox per label, a dash when only some of them have it; a tap sets it on all of them.
 * New label makes one and puts it on them; the pencil renames a label or deletes it (its moments stay).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelsSheet(
    labels: List<Label>, moments: List<LectureSummary>, onDismiss: () -> Unit,
    onSet: (add: Set<String>, remove: Set<String>) -> Unit, onNew: (String) -> Unit,
    onRename: (String, String) -> Unit, onDelete: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Label?>(null) }
    if (creating) NewLabelDialog(onDismiss = { creating = false }) { onNew(it); creating = false }
    editing?.let { l -> EditLabelDialog(l, onDismiss = { editing = null }, onRename = { onRename(l.id, it); editing = null }, onDelete = { onDelete(l.id); editing = null }) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                if (moments.size == 1) stringResource(R.string.notebooks_labels_of, isolated(moments.single().title)) else stringResource(R.string.notebooks_labels_of_many, moments.size),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(labels, key = { it.id }) { l ->
                    val count = moments.count { l.id in it.labels }
                    val toggle = when (count) {
                        0 -> ToggleableState.Off
                        moments.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { if (toggle == ToggleableState.On) onSet(emptySet(), setOf(l.id)) else onSet(setOf(l.id), emptySet()) }
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = toggle, onClick = null, modifier = Modifier.padding(12.dp))
                        LabelDot(l.color)
                        Text(l.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { editing = l }) { Icon(Icons.Filled.Edit, stringResource(R.string.notebooks_edit_label), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(R.string.notebooks_new_label), Modifier.padding(start = 16.dp))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 16.dp)) { Text(stringResource(R.string.notebooks_done)) }
        }
    }
}

@Composable
private fun NewLabelDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_new_label)) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.notebooks_new_name)) }, placeholder = { Text(stringResource(R.string.notebooks_new_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.notebooks_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

@Composable
private fun EditLabelDialog(label: Label, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(label.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_edit_label)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.notebooks_new_name)) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.notebooks_delete_label), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.notebooks_delete_label_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.notebooks_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

@Composable
private fun LanguageDialog(language: String, languages: List<String>, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_language_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(languages) { tag ->
                    Text(
                        languageName(tag), fontWeight = if (tag == language) FontWeight.SemiBold else null,
                        color = if (tag == language) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(tag) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

@Composable
private fun RecordingBanner(recording: RecordingNow, labels: List<Label>, onBack: () -> Unit) {
    val red = LocalTranscriptorColors.current.recording
    Surface(color = red.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onBack)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(red, CircleShape))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.notebooks_recording, formatClock(recording.elapsedMillis)), fontWeight = FontWeight.SemiBold, color = red)
                Text(
                    labels.joinToString(", ") { it.name }.ifEmpty { stringResource(R.string.notebooks_no_labels) },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = red, contentColor = Color.White)) { Text(stringResource(R.string.notebooks_back_to_lecture)) }
        }
    }
}

@Composable
private fun Empty() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.notebooks_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.notebooks_empty_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun weekdayAndTime(millis: Long): String = stringResource(
    R.string.notebooks_slot,
    SimpleDateFormat("EEEE", LocalConfiguration.current.locales[0]).format(Date(millis)),
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis)),
)

@Composable
private fun momentDay(millis: Long, now: Long): String = when (now / DAY - millis / DAY) {
    0L -> stringResource(R.string.notebooks_today)
    1L -> stringResource(R.string.notebooks_yesterday)
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}

@Composable
private fun momentLength(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    return when {
        minutes < 60 -> stringResource(R.string.notebooks_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.notebooks_hours, minutes / 60)
        else -> stringResource(R.string.notebooks_hours_minutes, minutes / 60, minutes % 60)
    }
}

/**
 * The languages a moment can be in, most likely first: the phone's, those in [used], those with
 * models of their own (Hebrew's), then every other language Whisper knows.
 */
@Composable
internal fun notebookLanguages(used: List<String> = emptyList()): List<String> {
    val locales = LocalConfiguration.current.locales
    return Languages.ordered((0 until locales.size()).map { locales[it].language }, used)
}

/** A label's color, from the palette by its [index]. */
@Composable
internal fun notebookColor(index: Int): Color = LocalTranscriptorColors.current.speakers.let { it[index % it.size] }

/** A language's name in the phone's language ("עברית", "English"). */
@Composable
internal fun languageName(tag: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return Locale.forLanguageTag(tag).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
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
