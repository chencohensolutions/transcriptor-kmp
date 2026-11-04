package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Notebook

/**
 * The notebooks on one page, each with its latest lectures under it (all of them a tap away) and its own record
 * button. "New lecture" asks which notebook, the last one used first. A lecture recording now is a banner on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebooksScreenA(state: NotebooksState, actions: NotebooksActions) {
    var picking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateListOf<String>() }
    if (creating) NotebooksNewDialog(onDismiss = { creating = false }) { t, l -> actions.newNotebook(t, l); creating = false }
    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text(
                stringResource(R.string.notebooks_a_record_in), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            state.notebooks.forEach { notebook ->
                Row(
                    Modifier.fillMaxWidth().clickable { picking = false; actions.record(notebook.id) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        notebook.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirectionOf(notebook.language)),
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (notebook.id == state.lastNotebookId) {
                        Text(stringResource(R.string.notebooks_last_used), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { picking = false; creating = true }.padding(horizontal = 24.dp, vertical = 14.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(R.string.notebooks_new_notebook), Modifier.padding(start = 12.dp))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { IconButton(onClick = actions.settings) { Icon(Icons.Filled.Settings, stringResource(R.string.notebooks_settings)) } },
            )
        },
        floatingActionButton = {
            if (state.recording == null && state.notebooks.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { picking = true },
                    icon = { Icon(Icons.Filled.Mic, null) },
                    text = { Text(stringResource(R.string.notebooks_new_lecture)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        ) {
            state.recording?.let { r ->
                item { NotebooksRecordingBanner(r, state.notebooks.firstOrNull { it.id == r.notebookId }, actions.backToRecording, Modifier.padding(16.dp)) }
            }
            if (state.notebooks.isEmpty()) item { NotebooksEmpty(onCreate = { creating = true }) }
            state.notebooks.forEach { notebook ->
                val open = notebook.id in expanded
                item(key = notebook.id) {
                    NotebookHeaderA(notebook, canRecord = state.recording == null) { actions.record(notebook.id) }
                }
                items(if (open) notebook.lectures else notebook.lectures.take(SHOWN), key = { notebook.id + "/" + it.id }) { lecture ->
                    NotebooksLectureRow(lecture, notebook, state, onOpen = { actions.openLecture(notebook.id, lecture.id) })
                }
                if (notebook.lectures.isEmpty()) {
                    item { Text(stringResource(R.string.notebooks_no_lectures), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (notebook.lectures.size > SHOWN) {
                    item {
                        TextButton(onClick = { if (open) expanded.remove(notebook.id) else expanded.add(notebook.id) }, Modifier.padding(horizontal = 8.dp)) {
                            Text(if (open) stringResource(R.string.notebooks_a_fewer) else stringResource(R.string.notebooks_a_all, notebook.lectures.size))
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            }
            if (state.notebooks.isNotEmpty()) {
                item {
                    TextButton(onClick = { creating = true }, Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.notebooks_new_notebook), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private const val SHOWN = 3

@Composable
private fun NotebookHeaderA(notebook: Notebook, canRecord: Boolean, onRecord: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                notebook.title, style = MaterialTheme.typography.titleLarge.copy(textDirection = textDirectionOf(notebook.language)),
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                languageName(notebook.language) + " · " + stringResource(R.string.notebooks_lectures, notebook.lectures.size) +
                    if (notebook.lectures.isNotEmpty()) " · " + lectureLength(notebook.lectures.sumOf { it.durationMillis }) else "",
                style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
            )
        }
        if (canRecord) {
            IconButton(onClick = onRecord) { Icon(Icons.Filled.Mic, stringResource(R.string.notebooks_record_here, notebook.title), tint = MaterialTheme.colorScheme.primary) }
        }
    }
}
