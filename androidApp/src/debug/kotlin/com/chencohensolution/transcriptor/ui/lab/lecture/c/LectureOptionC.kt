package com.chencohensolution.transcriptor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Segment

/**
 * The lecture while recording, as a document with a recorder: the transcript reads as paragraphs, notes are amber
 * tabs in the margin of the line they were written during (tap to open), and a voice-recorder bar sits at the bottom
 * with a big Note button. The note's time is taken when Note is pressed, not when it is saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectureScreenC(state: LiveLectureState, actions: LiveLectureActions) {
    val colors = LocalTranscriptorColors.current
    val list = rememberLazyListState()
    val segments = state.lecture.segments
    val notesBySegment = state.lecture.notes.groupBy { note -> segments.lastOrNull { it.startMillis <= note.atMillis }?.id }
    LaunchedEffect(segments.size, state.partial) { list.scrollToItem(segments.size) }
    var noteAt by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lecture_back)) }
            Column(Modifier.weight(1f)) {
                Text(state.notebookTitle, style = MaterialTheme.typography.labelMedium, color = colors.time)
                Text(state.lecture.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
            LazyColumn(state = list, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(segments, key = { it.id }) { seg -> ParagraphC(seg, notesBySegment[seg.id].orEmpty(), onClick = { actions.openSegment(seg) }) }
                item {
                    state.partial?.let {
                        Text(it + " …", style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, color = colors.partial, fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(start = 36.dp, end = 20.dp, top = 4.dp))
                    }
                }
            }
        }

        RecorderBarC(state, actions, onNote = { noteAt = state.elapsedMillis })
    }

    noteAt?.let { at ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        ModalBottomSheet(onDismissRequest = { noteAt = null }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.EditNote, null, tint = colors.onNote)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.lecture_note_at, formatClock(at)), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    RecordingDot(state.paused)
                    Spacer(Modifier.width(6.dp))
                    Text(formatClock(state.elapsedMillis), style = MaterialTheme.typography.labelLarge, color = colors.time)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.lecture_note_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { if (text.isNotBlank()) actions.addNote(at, text.trim()); noteAt = null },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.lecture_save_note)) }
            }
        }
    }
}

@Composable
private fun ParagraphC(seg: Segment, notes: List<Note>, onClick: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = 20.dp, top = 4.dp, bottom = 4.dp)) {
            // The margin: a tab per note written during this line.
            Box(Modifier.width(36.dp).padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
                if (notes.isNotEmpty()) {
                    Box(
                        Modifier.clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).background(colors.note).clickable { open = !open }
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.EditNote, null, tint = colors.onNote, modifier = Modifier.size(16.dp))
                            if (notes.size > 1) Text("${notes.size}", style = MaterialTheme.typography.labelSmall, color = colors.onNote)
                        }
                    }
                }
            }
            Text(seg.text, style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, lineHeight = 29.sp, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 36.dp, end = 20.dp, bottom = 6.dp)) {
                notes.forEach { note ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.note).padding(10.dp)) {
                        Text(formatClock(note.atMillis), style = MaterialTheme.typography.labelSmall, color = colors.onNote.copy(alpha = 0.7f))
                        Text(note.text, style = MaterialTheme.typography.bodyMedium, color = colors.onNote)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun RecorderBarC(state: LiveLectureState, actions: LiveLectureActions, onNote: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingDot(state.paused)
                Spacer(Modifier.width(8.dp))
                Text(
                    formatClock(state.elapsedMillis),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (state.paused) colors.time else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                LevelBars(state.level, height = 28)
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = actions.pauseOrResume, modifier = Modifier.size(48.dp)) {
                    Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(if (state.paused) R.string.lecture_resume else R.string.lecture_pause))
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = actions.stop, modifier = Modifier.size(48.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.recording, contentColor = Color.White),
                ) { Icon(Icons.Filled.Stop, stringResource(R.string.lecture_stop)) }
            }
            Text(
                if (state.paused) stringResource(R.string.lecture_paused) else stringResource(R.string.lecture_live_model, state.liveModelName, state.lagMillis / 1000f),
                style = MaterialTheme.typography.labelSmall, color = colors.time,
            )
            Spacer(Modifier.height(10.dp))
            ExtendedFloatingActionButton(
                onClick = onNote,
                containerColor = colors.note,
                contentColor = colors.onNote,
                icon = { Icon(Icons.Filled.EditNote, null) },
                text = { Text(stringResource(R.string.lecture_c_note), fontWeight = FontWeight.SemiBold) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
