package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Notebook

// Pieces the Notebooks options share: the banner of a lecture recording now, and a lecture's row.

/** A lecture is recording: its notebook, its clock, and the way back to it. */
@Composable
internal fun NotebooksRecordingBanner(recording: RecordingNow, notebook: Notebook?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val red = LocalTranscriptorColors.current.recording
    Surface(
        color = red.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().clickable(onClick = onBack),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(red, CircleShape))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.notebooks_recording, formatClock(recording.elapsedMillis)), fontWeight = FontWeight.SemiBold, color = red)
                notebook?.let {
                    Text(
                        it.title, style = MaterialTheme.typography.bodySmall.copy(textDirection = textDirectionOf(it.language)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = red, contentColor = Color.White)) {
                Text(stringResource(R.string.notebooks_back_to_lecture))
            }
        }
    }
}

/** A lecture in a list: its title in its notebook's direction, its facts, and the pass running on it, if one is. */
@Composable
internal fun NotebooksLectureRow(
    lecture: LectureSummary, notebook: Notebook, state: NotebooksState, onOpen: () -> Unit,
    modifier: Modifier = Modifier, showNotebook: Boolean = false, accent: Color? = null,
) {
    val progress = state.working[lecture.id]
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showNotebook) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                accent?.let { Box(Modifier.padding(end = 6.dp).size(8.dp).background(it, CircleShape)) }
                Text(
                    notebook.title, style = MaterialTheme.typography.labelMedium.copy(textDirection = textDirectionOf(notebook.language)),
                    color = accent ?: MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            lecture.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirectionOf(notebook.language)),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(lectureFacts(lecture, state.nowMillis), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
        if (progress != null) {
            Text(stringResource(R.string.notebooks_improving, (progress * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }
}

/** No notebooks yet: what one is, and the button that makes the first. */
@Composable
internal fun NotebooksEmpty(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.notebooks_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.notebooks_empty_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onCreate) { Text(stringResource(R.string.notebooks_new_notebook)) }
    }
}

/** A notebook's color: the speakers' palette, by its place in the list. */
@Composable
internal fun notebookColor(index: Int): Color = LocalTranscriptorColors.current.speakers.let { it[index % it.size] }
