package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Notebook

/**
 * A shelf of notebooks (a tile each: its color, name, language, how many lectures and when the last was), then a
 * notebook's own page: a big Record button on top and its lectures under it. A lecture recording now is a banner on
 * both.
 */
@Composable
fun NotebooksScreenB(state: NotebooksState, actions: NotebooksActions) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    if (creating) NotebooksNewDialog(onDismiss = { creating = false }) { t, l -> actions.newNotebook(t, l); creating = false }
    val index = state.notebooks.indexOfFirst { it.id == openId }
    if (index >= 0) {
        BackHandler { openId = null }
        NotebookPageB(state.notebooks[index], notebookColor(index), state, actions) { openId = null }
    } else {
        ShelfB(state, actions, onOpen = { openId = it }, onCreate = { creating = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfB(state: NotebooksState, actions: NotebooksActions, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { IconButton(onClick = actions.settings) { Icon(Icons.Filled.Settings, stringResource(R.string.notebooks_settings)) } },
            )
        },
    ) { padding ->
        if (state.notebooks.isEmpty()) {
            NotebooksEmpty(onCreate, Modifier.padding(padding))
            return@Scaffold
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.recording?.let { r ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NotebooksRecordingBanner(r, state.notebooks.firstOrNull { it.id == r.notebookId }, actions.backToRecording)
                }
            }
            itemsIndexed(state.notebooks, key = { _, n -> n.id }) { i, notebook ->
                NotebookTileB(notebook, notebookColor(i), state) { onOpen(notebook.id) }
            }
            item {
                OutlinedCard(onClick = onCreate, modifier = Modifier.heightIn(min = TILE_HEIGHT)) {
                    Column(Modifier.fillMaxWidth().heightIn(min = TILE_HEIGHT).padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.notebooks_new_notebook), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

private val TILE_HEIGHT = 150.dp

@Composable
private fun NotebookTileB(notebook: Notebook, color: Color, state: NotebooksState, onClick: () -> Unit) {
    val working = notebook.lectures.any { it.id in state.working }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)), border = BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(color))
        Column(Modifier.fillMaxWidth().heightIn(min = TILE_HEIGHT).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                notebook.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = textDirectionOf(notebook.language)),
                fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f, fill = false).height(8.dp))
            Text(languageName(notebook.language), style = MaterialTheme.typography.labelMedium, color = color)
            Text(stringResource(R.string.notebooks_lectures, notebook.lectures.size), style = MaterialTheme.typography.bodySmall)
            notebook.lectures.firstOrNull()?.let {
                Text(stringResource(R.string.notebooks_b_last, lectureDay(it.startedAtMillis, state.nowMillis)), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
            }
            if (working) Text(stringResource(R.string.notebooks_b_working), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (state.recording?.notebookId == notebook.id) {
                Text(stringResource(R.string.notebooks_recording, formatClock(state.recording.elapsedMillis)), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.recording)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookPageB(notebook: Notebook, color: Color, state: NotebooksState, actions: NotebooksActions, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = {
                    Text(
                        notebook.title, style = MaterialTheme.typography.titleLarge.copy(textDirection = textDirectionOf(notebook.language)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        languageName(notebook.language) + " · " + stringResource(R.string.notebooks_lectures, notebook.lectures.size) +
                            if (notebook.lectures.isNotEmpty()) " · " + lectureLength(notebook.lectures.sumOf { it.durationMillis }) else "",
                        style = MaterialTheme.typography.bodyMedium, color = color,
                    )
                    val r = state.recording
                    if (r != null) {
                        NotebooksRecordingBanner(r, state.notebooks.firstOrNull { it.id == r.notebookId }, actions.backToRecording)
                    } else {
                        Button(
                            onClick = { actions.record(notebook.id) }, shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Icon(Icons.Filled.Mic, null)
                            Text(stringResource(R.string.notebooks_b_record), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            if (notebook.lectures.isEmpty()) {
                item { Text(stringResource(R.string.notebooks_no_lectures), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { Spacer(Modifier.height(8.dp)) }
            items(notebook.lectures, key = { it.id }) { lecture ->
                NotebooksLectureRow(lecture, notebook, state, onOpen = { actions.openLecture(notebook.id, lecture.id) })
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}
