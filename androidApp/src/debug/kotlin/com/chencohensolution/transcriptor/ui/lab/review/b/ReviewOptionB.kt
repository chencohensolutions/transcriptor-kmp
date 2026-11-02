package com.chencohensolution.transcriptor.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker

/**
 * The lecture after recording in three tabs over a small player: Transcript (tap a line to play it, the pencil to
 * edit it in place), Notes (tap a time to jump there, add, edit, delete) and Speakers (each with their color, talk
 * time and lines, a sample to play, rename; and the two passes).
 */
@Composable
fun ReviewScreenB(state: ReviewState, actions: ReviewActions) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) }
                    Column(Modifier.weight(1f)) {
                        Text(state.lecture.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.notebookTitle, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
                    }
                }
                PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    listOf(R.string.review_b_transcript, R.string.review_b_notes, R.string.review_b_speakers).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) })
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> TranscriptTabB(state, actions)
                1 -> NotesTabB(state, actions)
                else -> SpeakersTabB(state, actions)
            }
        }
        MiniPlayerB(state, actions)
    }
}

@Composable
private fun TranscriptTabB(state: ReviewState, actions: ReviewActions) {
    var editing by remember { mutableStateOf<String?>(null) }
    val current = state.currentLine
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.lecture.segments, key = { it.id }) { line ->
                val speaker = state.lecture.speakers.firstOrNull { it.id == line.speakerId }
                if (editing == line.id) {
                    var text by remember { mutableStateOf(line.text) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        TextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                        Row {
                            TextButton(onClick = { actions.editLine(line.id, text); editing = null }) { Text(stringResource(R.string.review_save)) }
                            TextButton(onClick = { editing = null }) { Text(stringResource(R.string.review_cancel)) }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().background(if (line.id == current?.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.background)
                            .clickable { actions.playLine(line) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(speakerColor(line.speakerId)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (speaker?.let { speakerLabel(it) + " · " } ?: "") + formatClock(line.startMillis) + if (line.edited) " · " + stringResource(R.string.review_edited) else "",
                                style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time,
                            )
                            Text(line.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
                        }
                        IconButton(onClick = { editing = line.id }) { Icon(Icons.Filled.Edit, stringResource(R.string.review_edit_line), Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesTabB(state: ReviewState, actions: ReviewActions) {
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val colors = LocalTranscriptorColors.current
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.review_note_here, formatClock(state.positionMillis)))
                }
            }
            items(state.lecture.notes, key = { it.id }) { note ->
                val line = state.lecture.segments.lastOrNull { it.startMillis <= note.atMillis }
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.note).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(note.atMillis), style = MaterialTheme.typography.labelMedium, color = colors.onNote,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { actions.seek(note.atMillis) })
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { editing = note.id }) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = colors.onNote) }
                        IconButton(onClick = { actions.deleteNote(note.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.review_delete), Modifier.size(18.dp), tint = colors.onNote) }
                    }
                    Text(note.text, style = MaterialTheme.typography.bodyLarge, color = colors.onNote)
                    // What was being said when the note was started.
                    line?.let { Text("“${it.text}”", style = MaterialTheme.typography.bodySmall, color = colors.onNote.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
    if (adding) TextDialogB(stringResource(R.string.review_note_here, formatClock(state.positionMillis)), "", onDismiss = { adding = false }) { actions.addNote(state.positionMillis, it); adding = false }
    editing?.let { id ->
        val note = state.lecture.notes.first { it.id == id }
        TextDialogB(formatClock(note.atMillis), note.text, onDismiss = { editing = null }) { actions.editNote(id, it); editing = null }
    }
}

@Composable
private fun SpeakersTabB(state: ReviewState, actions: ReviewActions) {
    var renaming by remember { mutableStateOf<Speaker?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.lecture.speakers, key = { it.id }) { speaker ->
            val lines = state.lecture.segments.filter { it.speakerId == speaker.id }
            val talk = lines.sumOf { it.endMillis - it.startMillis }
            val share = talk.toFloat() / state.lecture.segments.sumOf { it.endMillis - it.startMillis }.coerceAtLeast(1)
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReviewSpeakerChip(speaker)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { renaming = speaker }) { Text(stringResource(R.string.review_rename)) }
                    IconButton(onClick = { lines.maxByOrNull { it.endMillis - it.startMillis }?.let(actions.playLine) }) {
                        Icon(Icons.Filled.PlayArrow, stringResource(R.string.review_b_sample))
                    }
                }
                Text(stringResource(R.string.review_b_talk, formatClock(talk), lines.size), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { share }, color = speakerColor(speaker.id), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        item { HorizontalDivider() }
        item { PassRowB(stringResource(R.string.review_b_refine_note, state.refineModelName), state.refine, Icons.Filled.AutoAwesome, stringResource(R.string.review_refine, "large-v3"), actions.refine) }
        item { PassRowB(stringResource(R.string.review_b_speakers_note), state.speakerPass, Icons.Filled.RecordVoiceOver, stringResource(R.string.review_find_speakers), actions.findSpeakers) }
    }
    renaming?.let { s -> TextDialogB(stringResource(R.string.review_rename), s.name, onDismiss = { renaming = null }) { actions.renameSpeaker(s.id, it); renaming = null } }
}

@Composable
private fun PassRowB(note: String, pass: PassState, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, start: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(note, style = MaterialTheme.typography.bodySmall)
        when (pass) {
            PassState.NotRun -> Button(onClick = start) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label) }
            is PassState.Running -> LinearProgressIndicator(progress = { pass.progress }, modifier = Modifier.fillMaxWidth())
            PassState.Done -> OutlinedButton(onClick = start) { Text(stringResource(R.string.review_b_again)) }
        }
    }
}

@Composable
private fun MiniPlayerB(state: ReviewState, actions: ReviewActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.playPause) { Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (state.playing) R.string.review_pause else R.string.review_play)) }
            Text(formatClock(state.positionMillis), style = MaterialTheme.typography.labelMedium)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Slider(
                    value = state.positionMillis.toFloat(), onValueChange = { actions.seek(it.toLong()) },
                    valueRange = 0f..state.lecture.durationMillis.toFloat(), modifier = Modifier.weight(1f).height(32.dp).padding(horizontal = 8.dp),
                )
            }
            TextButton(onClick = actions.cycleSpeed) { Text(stringResource(R.string.review_speed, state.speed.toString().removeSuffix(".0"))) }
        }
    }
}

@Composable
private fun TextDialogB(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { TextField(value = text, onValueChange = { text = it }) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.review_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) } },
    )
}
