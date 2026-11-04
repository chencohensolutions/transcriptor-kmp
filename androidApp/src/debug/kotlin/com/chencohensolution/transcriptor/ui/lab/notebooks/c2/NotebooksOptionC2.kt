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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Notebook
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Option c (lectures first) with lectures outside notebooks: Record can start in no notebook, and those lectures wait
 * on top ("Not in a notebook yet") with the notebook their time suggests, one tap to move them. The Record bar picks
 * the notebook its lectures usually are in at this hour and says so ("Usually Monday at 10:00"); a tap on it picks
 * another, or none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebooksScreenC2(state: NotebooksStateC2, actions: NotebooksActionsC2) {
    val base = state.notebooks
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    // The notebook picked for the next lecture: a notebook's id, NONE for none, null to follow the filter or the guess.
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var assigning by remember { mutableStateOf<LectureSummary?>(null) }
    var creatingFor by remember { mutableStateOf<LectureSummary?>(null) }
    if (creating) NotebooksNewDialog(onDismiss = { creating = false }) { t, l -> actions.notebooks.newNotebook(t, l); creating = false }
    creatingFor?.let { lecture ->
        NotebooksNewDialog(onDismiss = { creatingFor = null }) { t, l -> actions.assignToNew(lecture.id, t, l); creatingFor = null; assigning = null }
    }
    assigning?.let { lecture ->
        AssignDialogC2(lecture, base.notebooks, state.suggested[lecture.id]?.notebookId, onDismiss = { assigning = null }, onNew = { creatingFor = lecture }) {
            actions.assign(lecture.id, it); assigning = null
        }
    }

    val colors = base.notebooks.mapIndexed { i, n -> n.id to notebookColor(i) }.toMap()
    val chosenId = target ?: filter?.takeIf { it != UNFILED } ?: state.guess?.notebookId ?: NONE
    val recordIn = base.notebooks.firstOrNull { it.id == chosenId }
    val guessed = target == null && filter == null && state.guess != null && recordIn?.id == state.guess.notebookId
    val day = 24 * 60 * 60_000L
    val lectures = if (filter == UNFILED) emptyMap() else base.notebooks.filter { filter == null || it.id == filter }
        .flatMap { n -> n.lectures.map { n to it } }
        .sortedByDescending { it.second.startedAtMillis }
        .groupBy { (_, l) ->
            val days = base.nowMillis / day - l.startedAtMillis / day
            when {
                days == 0L -> R.string.notebooks_today
                days < 7 -> R.string.notebooks_c2_this_week
                else -> R.string.notebooks_c2_earlier
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { IconButton(onClick = actions.notebooks.settings) { Icon(Icons.Filled.Settings, stringResource(R.string.notebooks_settings)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                val r = base.recording
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    if (r != null) {
                        NotebooksRecordingBanner(
                            r, base.notebooks.firstOrNull { it.id == r.notebookId }, actions.notebooks.backToRecording,
                            noNotebook = stringResource(R.string.notebooks_c2_no_notebook_assign),
                        )
                    } else {
                        Column {
                            Box {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.notebooks_c2_records_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    AssistChip(
                                        onClick = { choosing = true },
                                        label = {
                                            Text(
                                                recordIn?.title ?: stringResource(R.string.notebooks_c2_no_notebook),
                                                style = MaterialTheme.typography.labelLarge.copy(textDirection = recordIn?.let { textDirectionOf(it.language) } ?: androidx.compose.ui.text.style.TextDirection.Content),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        leadingIcon = {
                                            if (recordIn != null) Box(Modifier.size(8.dp).background(colors.getValue(recordIn.id), CircleShape))
                                            else Icon(Icons.Filled.Inbox, null, Modifier.size(16.dp))
                                        },
                                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                                    val ordered = base.notebooks.sortedByDescending { it.id == state.guess?.notebookId }
                                    ordered.forEach { n ->
                                        DropdownMenuItem(
                                            text = { Text(n.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirectionOf(n.language))) },
                                            leadingIcon = { Box(Modifier.size(10.dp).background(colors.getValue(n.id), CircleShape)) },
                                            trailingIcon = if (n.id == state.guess?.notebookId) ({ Icon(Icons.Filled.AutoAwesome, stringResource(R.string.notebooks_c2_suggested), tint = MaterialTheme.colorScheme.primary) }) else null,
                                            onClick = { target = n.id; choosing = false },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notebooks_c2_no_notebook_assign)) },
                                        leadingIcon = { Icon(Icons.Filled.Inbox, null) },
                                        onClick = { target = NONE; choosing = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notebooks_new_notebook)) },
                                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                                        onClick = { choosing = false; creating = true },
                                    )
                                }
                            }
                            val why = when {
                                guessed -> state.guess?.let { stringResource(R.string.notebooks_c2_usually, usualSlot(it.usualStartMillis)) }
                                recordIn == null -> stringResource(R.string.notebooks_c2_assign_after)
                                else -> null
                            }
                            Row(Modifier.padding(top = 4.dp, bottom = 8.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (why != null) {
                                    if (guessed) Icon(Icons.Filled.AutoAwesome, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(why, style = MaterialTheme.typography.bodySmall, color = if (guessed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                            Button(
                                onClick = { actions.record(recordIn?.id) }, shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Icon(Icons.Filled.Mic, null)
                                Text(stringResource(R.string.notebooks_c2_record), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    item {
                        FilterChip(selected = filter == null, onClick = { filter = null; target = null }, label = { Text(stringResource(R.string.notebooks_c2_all)) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    if (state.unfiled.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = filter == UNFILED, onClick = { filter = if (filter == UNFILED) null else UNFILED; target = null },
                                label = { Text(stringResource(R.string.notebooks_c2_unfiled_chip, state.unfiled.size)) },
                                leadingIcon = { Icon(Icons.Filled.Inbox, null, Modifier.size(16.dp)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(base.notebooks, key = { _, n -> n.id }) { i, n ->
                        FilterChip(
                            selected = filter == n.id, onClick = { filter = if (filter == n.id) null else n.id; target = null },
                            label = { Text(n.title, style = MaterialTheme.typography.labelLarge.copy(textDirection = textDirectionOf(n.language)), maxLines = 1) },
                            leadingIcon = { Box(Modifier.size(8.dp).background(notebookColor(i), CircleShape)) },
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
                item(key = UNFILED) {
                    Text(
                        stringResource(R.string.notebooks_c2_unfiled), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(state.unfiled, key = { "unfiled/" + it.id }) { lecture ->
                    val suggestion = state.suggested[lecture.id]?.let { g -> base.notebooks.firstOrNull { it.id == g.notebookId } }
                    UnfiledRowC2(
                        lecture, base, suggestion, suggestion?.let { colors[it.id] },
                        onOpen = { actions.notebooks.openLecture("", lecture.id) },
                        onMove = { suggestion?.let { actions.assign(lecture.id, it.id) } },
                        onAssign = { assigning = lecture },
                    )
                }
            }
            if (base.notebooks.isEmpty() && state.unfiled.isEmpty()) {
                item { NotebooksEmpty(onCreate = { creating = true }) }
            }
            lectures.forEach { (group, rows) ->
                item(key = group) {
                    Text(
                        stringResource(group), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(rows, key = { (n, l) -> n.id + "/" + l.id }) { (notebook, lecture) ->
                    NotebooksLectureRow(
                        lecture, notebook, base, onOpen = { actions.notebooks.openLecture(notebook.id, lecture.id) },
                        showNotebook = filter == null, accent = colors[notebook.id],
                    )
                }
            }
        }
    }
}

private const val NONE = ""
private const val UNFILED = "unfiled"

/** "Monday at 10:00": the weekday and time of a lecture, in the phone's zone. */
@Composable
private fun usualSlot(millis: Long): String = stringResource(
    R.string.notebooks_c2_slot,
    SimpleDateFormat("EEEE", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(millis)),
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis)),
)

/** A lecture not in a notebook: its facts, and Move to the suggested notebook (or Assign, without a suggestion). */
@Composable
private fun UnfiledRowC2(
    lecture: LectureSummary, state: NotebooksState, suggestion: Notebook?, color: Color?,
    onOpen: () -> Unit, onMove: () -> Unit, onAssign: () -> Unit,
) {
    val progress = state.working[lecture.id]
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(lecture.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Its title is its date: the weekday and time say more, and are what the suggestion goes by.
        Text(
            listOfNotNull(
                usualSlot(lecture.startedAtMillis), lectureLength(lecture.durationMillis),
                if (lecture.noteCount > 0) stringResource(R.string.notebooks_notes, lecture.noteCount) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        )
        if (progress != null) {
            Text(stringResource(R.string.notebooks_improving, (progress * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (suggestion != null) {
                FilledTonalButton(onClick = onMove, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    color?.let { Box(Modifier.padding(end = 6.dp).size(8.dp).background(it, CircleShape)) }
                    Text(
                        stringResource(R.string.notebooks_c2_move_to, isolated(suggestion.title)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                TextButton(onClick = onAssign) { Text(stringResource(R.string.notebooks_c2_other)) }
            } else {
                OutlinedButton(onClick = onAssign) { Text(stringResource(R.string.notebooks_c2_assign)) }
            }
        }
    }
}

/** Which notebook an unfiled lecture goes in: the suggested one first, or a new one. */
@Composable
private fun AssignDialogC2(
    lecture: LectureSummary, notebooks: List<Notebook>, suggested: String?,
    onDismiss: () -> Unit, onNew: () -> Unit, onAssign: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_c2_assign_title, isolated(lecture.title))) },
        text = {
            Column {
                notebooks.sortedByDescending { it.id == suggested }.forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable { onAssign(n.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            n.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirectionOf(n.language)),
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (n.id == suggested) Text(stringResource(R.string.notebooks_c2_suggested), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                HorizontalDivider()
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
