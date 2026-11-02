package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.TimelineEntry
import com.chencohensolution.transcriptor.notebook.timelineOf

/**
 * The lecture after recording, as the recording screen's timeline with a player: lines and notes in order, the line
 * being played highlighted and followed; tap a line to play from it, hold it to edit its text or speaker. The
 * scrubber shows the speakers' turns in their colors and the notes as marks. The passes (refine, speakers) are chips
 * under the title.
 */
@Composable
fun ReviewScreenA(state: ReviewState, actions: ReviewActions) {
    val entries = remember(state.lecture.segments, state.lecture.notes) { timelineOf(state.lecture) }
    val current = state.currentLine
    val list = rememberLazyListState()
    var editing by remember { mutableStateOf<Segment?>(null) }
    var noting by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(current?.id, state.playing) {
        val i = entries.indexOfFirst { it is TimelineEntry.Speech && it.segment.id == current?.id }
        if (state.playing && i >= 0) list.animateScrollToItem(i)
    }
    editing?.let { line -> EditLineDialogA(line, state.lecture.speakers, onDismiss = { editing = null }) { text, speaker ->
        if (text != line.text) actions.editLine(line.id, text)
        if (speaker != null && speaker != line.speakerId) actions.setLineSpeaker(line.id, speaker)
        editing = null
    } }
    noting?.let { at -> NoteDialogA(at, onDismiss = { noting = null }) { actions.addNote(at, it); noting = null } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) }
                    Column(Modifier.weight(1f)) {
                        Text(state.lecture.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${state.notebookTitle} · ${formatClock(state.lecture.durationMillis)}", style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
                    }
                }
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassChipA(state.refine, Icons.Filled.AutoAwesome, actions.refine,
                        idle = stringResource(R.string.review_refine, "large-v3"),
                        running = { stringResource(R.string.review_refining, "large-v3", it) },
                        done = stringResource(R.string.review_refined, "large-v3"))
                    PassChipA(state.speakerPass, Icons.Filled.RecordVoiceOver, actions.findSpeakers,
                        idle = stringResource(R.string.review_find_speakers),
                        running = { stringResource(R.string.review_finding_speakers, it) },
                        done = stringResource(R.string.review_speakers_found))
                }
                HorizontalDivider()
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
            LazyColumn(state = list, contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.weight(1f)) {
                items(entries, key = { if (it is TimelineEntry.Speech) it.segment.id else (it as TimelineEntry.Written).note.id }) { entry ->
                    when (entry) {
                        is TimelineEntry.Speech -> {
                            val line = entry.segment
                            val previous = state.lecture.segments.getOrNull(state.lecture.segments.indexOf(line) - 1)
                            LineRowA(
                                line, current = line.id == current?.id,
                                speaker = if (line.speakerId != previous?.speakerId) state.lecture.speakers.firstOrNull { it.id == line.speakerId } else null,
                                onPlay = { actions.playLine(line) }, onEdit = { editing = line },
                            )
                        }
                        is TimelineEntry.Written -> NoteRowA(entry.note.atMillis, entry.note.text) { actions.seek(entry.note.atMillis) }
                    }
                }
            }
        }
        PlayerA(state, actions, onNote = { noting = state.positionMillis })
    }
}

@Composable
private fun PassChipA(pass: PassState, icon: androidx.compose.ui.graphics.vector.ImageVector, start: () -> Unit, idle: String, running: @Composable (Int) -> String, done: String) {
    when (pass) {
        PassState.NotRun -> AssistChip(onClick = start, label = { Text(idle) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
        is PassState.Running -> AssistChip(onClick = {}, label = {
            Column { Text(running((pass.progress * 100).toInt())); LinearProgressIndicator(progress = { pass.progress }, modifier = Modifier.width(120.dp).padding(top = 2.dp)) }
        })
        PassState.Done -> FilterChip(selected = true, onClick = {}, label = { Text(done) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineRowA(line: Segment, current: Boolean, speaker: Speaker?, onPlay: () -> Unit, onEdit: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    Column(
        Modifier.fillMaxWidth()
            .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onPlay, onLongClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (speaker != null) ReviewSpeakerChip(speaker, Modifier.padding(start = 44.dp, bottom = 4.dp))
        Row {
            Text(formatClock(line.startMillis), style = MaterialTheme.typography.labelSmall, color = if (current) MaterialTheme.colorScheme.primary else colors.time,
                modifier = Modifier.width(44.dp).padding(top = 3.dp))
            Column {
                Text(line.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal)
                if (line.edited) Text(stringResource(R.string.review_edited), style = MaterialTheme.typography.labelSmall, color = colors.time, fontStyle = FontStyle.Italic)
            }
        }
    }
}

@Composable
private fun NoteRowA(at: Long, text: String, onTime: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    Row(Modifier.padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.note).padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 520.dp)) {
            Icon(Icons.Filled.EditNote, null, tint = colors.onNote, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(formatClock(at), style = MaterialTheme.typography.labelSmall, color = colors.onNote.copy(alpha = 0.8f),
                    modifier = Modifier.combinedClickableCompat(onTime))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onNote)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

/** The player: the speakers' turns and the notes along the lecture, the playhead; play, time, speed, a note here. */
@Composable
private fun PlayerA(state: ReviewState, actions: ReviewActions, onNote: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    val duration = state.lecture.durationMillis.coerceAtLeast(1)
    val turnColors = state.lecture.segments.map { speakerColor(it.speakerId) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val head = MaterialTheme.colorScheme.primary
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // The scrubber runs with time, left to right, in every language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Canvas(
                    Modifier.fillMaxWidth().height(28.dp)
                        .pointerInput(duration) { detectTapGestures { actions.seek((it.x / size.width * duration).toLong()) } }
                        .pointerInput(duration) { detectHorizontalDragGestures { change, _ -> actions.seek((change.position.x / size.width * duration).toLong()) } },
                ) {
                    val y = size.height / 2
                    drawRoundRect(track, Offset(0f, y - 4.dp.toPx()), Size(size.width, 8.dp.toPx()), CornerRadius(4.dp.toPx()))
                    state.lecture.segments.forEachIndexed { i, s ->
                        val x0 = s.startMillis.toFloat() / duration * size.width
                        val x1 = (s.endMillis.toFloat() / duration * size.width).coerceAtLeast(x0 + 2f)
                        drawRect(turnColors[i], Offset(x0, y - 4.dp.toPx()), Size(x1 - x0, 8.dp.toPx()))
                    }
                    state.lecture.notes.forEach { n ->
                        val x = n.atMillis.toFloat() / duration * size.width
                        drawRect(colors.onNote, Offset(x - 1.dp.toPx(), y - 11.dp.toPx()), Size(2.dp.toPx(), 5.dp.toPx()))
                    }
                    val px = state.positionMillis.toFloat() / duration * size.width
                    drawCircle(head, 7.dp.toPx(), Offset(px, y))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatClock(state.positionMillis)} / ${formatClock(state.lecture.durationMillis)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.cycleSpeed) { Text(stringResource(R.string.review_speed, state.speed.toString().removeSuffix(".0"))) }
                FilledIconButton(onClick = actions.playPause, modifier = Modifier.size(48.dp)) {
                    Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (state.playing) R.string.review_pause else R.string.review_play))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    IconButton(onClick = onNote) { Icon(Icons.Filled.EditNote, stringResource(R.string.review_note_here, formatClock(state.positionMillis))) }
                }
            }
        }
    }
}

@Composable
private fun EditLineDialogA(line: Segment, speakers: List<Speaker>, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var text by remember { mutableStateOf(line.text) }
    var speaker by remember { mutableStateOf(line.speakerId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_edit_line) + " · " + formatClock(line.startMillis)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = text, onValueChange = { text = it }, minLines = 3)
                Text(stringResource(R.string.review_speaker), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    speakers.forEach { s -> FilterChip(selected = speaker == s.id, onClick = { speaker = s.id }, label = { Text(speakerLabel(s), color = speakerColor(s.id)) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, speaker) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.review_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) } },
    )
}

@Composable
private fun NoteDialogA(at: Long, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_note_here, formatClock(at))) },
        text = { TextField(value = text, onValueChange = { text = it }, placeholder = { Text(stringResource(R.string.review_note_hint)) }, minLines = 2) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.review_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) } },
    )
}
