package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R

/**
 * Lectures first: every lecture, newest first, grouped by when (today, this week, earlier), each with its notebook's
 * color and name; chips on top narrow it to one notebook. At the bottom, one Record button with the notebook it
 * records in (the one filtered to, else the last used), which a tap changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebooksScreenC(state: NotebooksState, actions: NotebooksActions) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    if (creating) NotebooksNewDialog(onDismiss = { creating = false }) { t, l -> actions.newNotebook(t, l); creating = false }
    val colors = state.notebooks.mapIndexed { i, n -> n.id to notebookColor(i) }.toMap()
    val recordIn = state.notebooks.firstOrNull { it.id == (target ?: filter ?: state.lastNotebookId) } ?: state.notebooks.firstOrNull()
    val day = 24 * 60 * 60_000L
    val lectures = state.notebooks.filter { filter == null || it.id == filter }
        .flatMap { n -> n.lectures.map { n to it } }
        .sortedByDescending { it.second.startedAtMillis }
        .groupBy { (_, l) ->
            val days = state.nowMillis / day - l.startedAtMillis / day
            when {
                days == 0L -> R.string.notebooks_today
                days < 7 -> R.string.notebooks_c_this_week
                else -> R.string.notebooks_c_earlier
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
            if (state.notebooks.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    val r = state.recording
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        if (r != null) {
                            NotebooksRecordingBanner(r, state.notebooks.firstOrNull { it.id == r.notebookId }, actions.backToRecording)
                        } else if (recordIn != null) {
                            Column {
                                Box {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                        Text(stringResource(R.string.notebooks_c_records_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        AssistChip(
                                            onClick = { choosing = true },
                                            label = {
                                                Text(
                                                    recordIn.title, style = MaterialTheme.typography.labelLarge.copy(textDirection = textDirectionOf(recordIn.language)),
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            leadingIcon = { Box(Modifier.size(8.dp).background(colors.getValue(recordIn.id), CircleShape)) },
                                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                                        state.notebooks.forEach { n ->
                                            DropdownMenuItem(
                                                text = { Text(n.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirectionOf(n.language))) },
                                                leadingIcon = { Box(Modifier.size(10.dp).background(colors.getValue(n.id), CircleShape)) },
                                                onClick = { target = n.id; choosing = false },
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.notebooks_new_notebook)) },
                                            leadingIcon = { Icon(Icons.Filled.Add, null) },
                                            onClick = { choosing = false; creating = true },
                                        )
                                    }
                                }
                                Button(
                                    onClick = { actions.record(recordIn.id) }, shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                ) {
                                    Icon(Icons.Filled.Mic, null)
                                    Text(stringResource(R.string.notebooks_c_record), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.notebooks.isEmpty()) {
            NotebooksEmpty(onCreate = { creating = true }, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    item {
                        FilterChip(selected = filter == null, onClick = { filter = null; target = null }, label = { Text(stringResource(R.string.notebooks_c_all)) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    itemsIndexed(state.notebooks, key = { _, n -> n.id }) { i, n ->
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
            if (lectures.isEmpty()) {
                item { Text(stringResource(R.string.notebooks_no_lectures), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                        lecture, notebook, state, onOpen = { actions.openLecture(notebook.id, lecture.id) },
                        showNotebook = filter == null, accent = colors[notebook.id],
                    )
                }
            }
        }
    }
}
