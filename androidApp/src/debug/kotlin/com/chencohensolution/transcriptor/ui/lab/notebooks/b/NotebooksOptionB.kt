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
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SuggestionChip
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Option b — labels after, one list. Record has only the language: a moment is labeled once it is recorded. Every
 * moment is in one list by time; one without labels shows the label its time suggests (✦ Statistics) to accept with a
 * tap, and every moment has a small + for its labels sheet. The filter button in the top bar narrows the list to one
 * or more labels (a moment with any of them), shown as chips under the bar to clear.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotebooksScreenB(all: NotebooksOptionBState, actions: NotebooksOptionBActions) {
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val state = all.copy(moments = all.moments.filter { it.summary.id !in hidden })
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

    // The labels the list is narrowed to (NO_LABEL for moments without any); empty for every moment.
    var filters by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var filtering by remember { mutableStateOf(false) }
    var language by rememberSaveable(state.language) { mutableStateOf(state.language) }
    var labeling by remember { mutableStateOf<List<String>?>(null) }
    var choosingLanguage by remember { mutableStateOf(false) }

    val labelsById = state.labels.associateBy { it.id }
    val shown = state.moments.filter { m -> filters.isEmpty() || m.labelIds.any { it in filters } || (NO_LABEL in filters && m.labelIds.isEmpty()) }
        .groupBy { m ->
            val days = state.nowMillis / DAY_B - m.summary.startedAtMillis / DAY_B
            when {
                days == 0L -> R.string.notebooks_today
                days < 7 -> R.string.notebooks_this_week
                else -> R.string.notebooks_earlier
            }
        }

    labeling?.let { ids ->
        LabelsSheetB(
            labels = state.labels, moments = state.moments.filter { it.summary.id in ids },
            onDismiss = { labeling = null; selected = emptySet() },
            onSet = { add, remove -> actions.setLabels(ids, add, remove) },
            onNew = { name -> actions.newLabel(name).also { actions.setLabels(ids, setOf(it), emptySet()) } },
            onRename = actions.renameLabel, onDelete = actions.deleteLabel,
        )
    }
    if (choosingLanguage) {
        LanguageDialogB(language, notebookLanguages(state.moments.map { it.language }), onDismiss = { choosingLanguage = false }) { language = it; choosingLanguage = false }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        Box {
                            IconButton(onClick = { filtering = true }) {
                                Icon(Icons.Filled.FilterList, stringResource(R.string.notebooks_b_filter), tint = if (filters.isEmpty()) LocalContentColor.current else MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(expanded = filtering, onDismissRequest = { filtering = false }) {
                                state.labels.forEach { l ->
                                    DropdownMenuItem(
                                        text = { Text(l.name) },
                                        leadingIcon = { TriStateCheckbox(state = if (l.id in filters) ToggleableState.On else ToggleableState.Off, onClick = null) },
                                        trailingIcon = { Text(state.moments.count { l.id in it.labelIds }.toString(), style = MaterialTheme.typography.labelMedium, color = notebookColor(l.color)) },
                                        onClick = { filters = if (l.id in filters) filters - l.id else filters + l.id },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notebooks_b_no_labels)) },
                                    leadingIcon = { TriStateCheckbox(state = if (NO_LABEL in filters) ToggleableState.On else ToggleableState.Off, onClick = null) },
                                    trailingIcon = { Text(state.moments.count { it.labelIds.isEmpty() }.toString(), style = MaterialTheme.typography.labelMedium) },
                                    onClick = { filters = if (NO_LABEL in filters) filters - NO_LABEL else filters + NO_LABEL },
                                )
                            }
                        }
                        IconButton(onClick = actions.settings) { Icon(Icons.Filled.Settings, stringResource(R.string.notebooks_settings)) }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.notebooks_clear_selection)) } },
                    title = { Text(stringResource(R.string.notebooks_selected, selected.size)) },
                    actions = {
                        IconButton(onClick = { labeling = selected.toList() }) { Icon(Icons.AutoMirrored.Outlined.Label, stringResource(R.string.notebooks_b_labels)) }
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
                        RecordingBannerB(r, emptyList(), actions.backToRecording)
                    } else {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.notebooks_b_label_after), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                AssistChip(
                                    onClick = { choosingLanguage = true },
                                    label = { Text(languageName(language), maxLines = 1) },
                                    leadingIcon = { Icon(Icons.Filled.Language, null, Modifier.size(16.dp)) },
                                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            Button(
                                onClick = { actions.record(emptyList(), language) }, shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(56.dp),
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
            if (filters.isNotEmpty()) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(filters.toList()) { id ->
                            val l = labelsById[id]
                            InputChip(
                                selected = true, onClick = { filters = filters - id },
                                label = { Text(l?.name ?: stringResource(R.string.notebooks_b_no_labels), maxLines = 1) },
                                leadingIcon = l?.let { { LabelDotB(it.color) } },
                                trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.notebooks_clear_selection), Modifier.size(16.dp)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
            }
            if (state.moments.isEmpty()) item { EmptyB() }
            shown.forEach { (group, rows) ->
                item(key = group) { GroupTitleB(stringResource(group)) }
                items(rows, key = { it.summary.id }) { m ->
                    val suggestion = state.suggested[m.summary.id]?.let { labelsById[it] }
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) {
                            MomentRowB(
                                m, m.labelIds.mapNotNull { labelsById[it] }, state, selected = m.summary.id in selected,
                                onOpen = { if (selected.isEmpty()) actions.openMoment(m.summary.id) else toggle(m.summary.id) },
                                onLongPress = { toggle(m.summary.id) },
                            ) {
                                if (m.labelIds.isEmpty() && suggestion != null) {
                                    SuggestionChip(
                                        onClick = { actions.setLabels(listOf(m.summary.id), setOf(suggestion.id), emptySet()) },
                                        label = { Text(stringResource(R.string.notebooks_b_add, isolated(suggestion.name)), maxLines = 1) },
                                        icon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
                                    )
                                }
                            }
                        }
                        if (selected.isEmpty()) {
                            IconButton(onClick = { labeling = listOf(m.summary.id) }, modifier = Modifier.padding(top = 4.dp, end = 4.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.Label, stringResource(R.string.notebooks_b_labels), tint = if (m.labelIds.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val NO_LABEL = "-"
private const val DAY_B = 24 * 60 * 60_000L

@Composable
private fun LabelDotB(color: Int) = Box(Modifier.size(8.dp).background(notebookColor(color), CircleShape))

/** A moment's labels, in their colors: "● Psychology  ● Exam". */

@Composable
private fun LabelPillsB(labels: List<NotebooksOptionBLabel>) {
    if (labels.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelDotB(l.color)
                Text(l.name, style = MaterialTheme.typography.labelMedium, color = notebookColor(l.color), maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun GroupTitleB(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentRowB(
    m: NotebooksOptionBMoment, labels: List<NotebooksOptionBLabel>, state: NotebooksOptionBState, selected: Boolean,
    onOpen: () -> Unit, onLongPress: () -> Unit, extra: @Composable () -> Unit = {},
) {
    val l = m.summary
    Column(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LabelPillsB(labels)
        Text(l.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = titleDirection(m.language)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                if (m.labelIds.isEmpty()) weekdayAndTimeB(l.startedAtMillis) else dayB(l.startedAtMillis, state.nowMillis),
                lengthB(l.durationMillis),
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
private fun LabelsMenuB(
    expanded: Boolean, labels: List<NotebooksOptionBLabel>, checked: Set<String>, guessed: Set<String>,
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
                        LabelDotB(l.color)
                    }
                },
                onClick = { onToggle(l.id) },
            )
        }
        if (labels.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.notebooks_b_new_label)) }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = onNew)
    }
}

/**
 * The labels of [moments]: a checkbox per label, a dash when only some of them have it; a tap sets it on all of them.
 * New label makes one and puts it on them; the pencil renames a label or deletes it (its moments stay).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelsSheetB(
    labels: List<NotebooksOptionBLabel>, moments: List<NotebooksOptionBMoment>, onDismiss: () -> Unit,
    onSet: (add: Set<String>, remove: Set<String>) -> Unit, onNew: (String) -> Unit,
    onRename: (String, String) -> Unit, onDelete: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NotebooksOptionBLabel?>(null) }
    if (creating) NewLabelDialogB(onDismiss = { creating = false }) { onNew(it); creating = false }
    editing?.let { l -> EditLabelDialogB(l, onDismiss = { editing = null }, onRename = { onRename(l.id, it); editing = null }, onDelete = { onDelete(l.id); editing = null }) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                if (moments.size == 1) stringResource(R.string.notebooks_b_labels_of, isolated(moments.single().summary.title)) else stringResource(R.string.notebooks_b_labels_of_many, moments.size),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(labels, key = { it.id }) { l ->
                    val count = moments.count { l.id in it.labelIds }
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
                        LabelDotB(l.color)
                        Text(l.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { editing = l }) { Icon(Icons.Filled.Edit, stringResource(R.string.notebooks_b_edit_label), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(R.string.notebooks_b_new_label), Modifier.padding(start = 16.dp))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 16.dp)) { Text(stringResource(R.string.notebooks_b_done)) }
        }
    }
}

@Composable
private fun NewLabelDialogB(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_b_new_label)) },
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
private fun EditLabelDialogB(label: NotebooksOptionBLabel, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(label.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_b_edit_label)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.notebooks_new_name)) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.notebooks_b_delete_label), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.notebooks_b_delete_label_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.notebooks_b_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

@Composable
private fun LanguageDialogB(language: String, languages: List<String>, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_b_language_title)) },
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
private fun RecordingBannerB(recording: NotebooksOptionBRecording, labels: List<NotebooksOptionBLabel>, onBack: () -> Unit) {
    val red = LocalTranscriptorColors.current.recording
    Surface(color = red.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onBack)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(red, CircleShape))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.notebooks_recording, formatClock(recording.elapsedMillis)), fontWeight = FontWeight.SemiBold, color = red)
                Text(
                    labels.joinToString(", ") { it.name }.ifEmpty { stringResource(R.string.notebooks_b_no_labels) },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = red, contentColor = Color.White)) { Text(stringResource(R.string.notebooks_back_to_lecture)) }
        }
    }
}

@Composable
private fun EmptyB() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.notebooks_b_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.notebooks_b_empty_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun weekdayAndTimeB(millis: Long): String = stringResource(
    R.string.notebooks_slot,
    SimpleDateFormat("EEEE", LocalConfiguration.current.locales[0]).format(Date(millis)),
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis)),
)

@Composable
private fun dayB(millis: Long, now: Long): String = when (now / DAY_B - millis / DAY_B) {
    0L -> stringResource(R.string.notebooks_today)
    1L -> stringResource(R.string.notebooks_yesterday)
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}

@Composable
private fun lengthB(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    return when {
        minutes < 60 -> stringResource(R.string.notebooks_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.notebooks_hours, minutes / 60)
        else -> stringResource(R.string.notebooks_hours_minutes, minutes / 60, minutes % 60)
    }
}
