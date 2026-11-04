package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.review.PassState
import com.chencohensolution.transcriptor.review.ReviewState

class ReviewActions(
    val back: () -> Unit = {},
    val playPause: () -> Unit = {},
    val seek: (atMillis: Long) -> Unit = {},
    /** Plays from a line's start. */
    val playLine: (Segment) -> Unit = {},
    val cycleSpeed: () -> Unit = {},
    /** The user's text for a line: marked edited, kept by the refine pass. */
    val editLine: (id: String, text: String) -> Unit = { _, _ -> },
    /** Moves a line to another speaker. */
    val setLineSpeaker: (lineId: String, speakerId: String) -> Unit = { _, _ -> },
    val renameSpeaker: (id: String, name: String) -> Unit = { _, _ -> },
    /** Runs the refine pass with a model (a [com.chencohensolution.transcriptor.review.ModelChoice]'s id). */
    val refine: (modelId: String) -> Unit = {},
    /** Makes an earlier transcript (a version's id) the current one, keeping this one as a version. */
    val switchVersion: (id: String) -> Unit = {},
    val findSpeakers: () -> Unit = {},
    val addNote: (atMillis: Long, text: String) -> Unit = { _, _ -> },
    val editNote: (id: String, text: String) -> Unit = { _, _ -> },
    val deleteNote: (id: String) -> Unit = {},
)

/**
 * A recorded lecture, as a document: paragraphs, one per speaker turn, that read like text; tap a sentence to play it,
 * a speaker's chip to name them. The pencil turns the paragraphs into fields (each line with its speaker, to move it)
 * that are saved on Done. Notes are pills after the paragraph they were written in (tap the time to jump, hold to edit
 * or delete). At the bottom, the overview of the whole lecture (a lane per speaker, the notes, the playhead; tap to
 * jump) and the player. The passes over the recording are in the menu, with a progress banner while one runs.
 */
@Composable
fun ReviewScreen(state: ReviewState, actions: ReviewActions) {
    var editing by remember { mutableStateOf(false) }
    val edits = remember { mutableStateMapOf<String, String>() }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Speaker?>(null) }
    var moving by remember { mutableStateOf<Segment?>(null) }
    var note by remember { mutableStateOf<Note?>(null) }
    var noting by remember { mutableStateOf<Long?>(null) }
    var pickingModel by remember { mutableStateOf(false) }
    var showingVersions by remember { mutableStateOf(false) }
    val turns = remember(state.lecture.segments) { turnsOf(state.lecture.segments) }

    renaming?.let { s -> RenameSpeakerDialog(s, onDismiss = { renaming = null }) { actions.renameSpeaker(s.id, it); renaming = null } }
    moving?.let { line -> MoveLineDialog(line, state.lecture.speakers, onDismiss = { moving = null }) { actions.setLineSpeaker(line.id, it); moving = null } }
    note?.let { n -> NoteDialog(stringResource(R.string.review_note_here, formatClock(n.atMillis)), n.text, onDelete = { actions.deleteNote(n.id); note = null }, onDismiss = { note = null }) { actions.editNote(n.id, it); note = null } }
    if (pickingModel) RefineDialog(state, onDismiss = { pickingModel = false }) { actions.refine(it); pickingModel = false }
    if (showingVersions) VersionsDialog(state, onDismiss = { showingVersions = false }) { actions.switchVersion(it); showingVersions = false }
    noting?.let { at -> NoteDialog(stringResource(R.string.review_note_here, formatClock(at)), "", onDelete = null, onDismiss = { noting = null }) { actions.addNote(at, it); noting = null } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) }
                    Column(Modifier.weight(1f)) {
                        Text(state.lecture.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = titleDirection(state.lecture.language)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.notebookTitle, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time, maxLines = 1)
                    }
                    IconButton(onClick = {
                        if (editing) {
                            edits.forEach { (id, text) -> actions.editLine(id, text) }
                            edits.clear()
                        }
                        editing = !editing
                    }) {
                        Icon(if (editing) Icons.Filled.Check else Icons.Filled.Edit, stringResource(if (editing) R.string.review_done else R.string.review_edit_text))
                    }
                    if (state.refine != null || state.speakerPass != null || state.lecture.versions.isNotEmpty()) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (state.refine != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_refine_with)) }, enabled = state.refine !is PassState.Running,
                                onClick = { pickingModel = true; menu = false },
                            )
                            if (state.lecture.versions.isNotEmpty()) DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_versions, state.lecture.versions.size + 1)) },
                                enabled = state.refine !is PassState.Running,
                                onClick = { showingVersions = true; menu = false },
                            )
                            if (state.speakerPass != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_find_speakers)) }, enabled = state.speakerPass !is PassState.Running,
                                onClick = { actions.findSpeakers(); menu = false },
                            )
                        }
                    }
                }
                (state.refine as? PassState.Running)?.let { Banner(stringResource(R.string.review_refining, (it.progress * 100).toInt()), it.progress) }
                (state.speakerPass as? PassState.Running)?.let { Banner(stringResource(R.string.review_finding_speakers, (it.progress * 100).toInt()), it.progress) }
                HorizontalDivider()
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.weight(1f)) {
                items(turns, key = { it.first().id }) { turn ->
                    val next = turns.getOrNull(turns.indexOf(turn) + 1)?.first()?.startMillis ?: Long.MAX_VALUE
                    val notes = state.lecture.notes.filter { n -> n.atMillis >= turn.first().startMillis && n.atMillis < next || (turn === turns.first() && n.atMillis < turn.first().startMillis) }
                    Turn(
                        turn, state, actions, editing, edits, notes,
                        onSpeaker = { renaming = it }, onMove = { moving = it }, onNote = { note = it },
                    )
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
            Column(Modifier.navigationBarsPadding().padding(top = 8.dp)) {
                Overview(state, actions)
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatClock(state.positionMillis)} / ${formatClock(state.lecture.durationMillis)}", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f).padding(start = 8.dp))
                    FilledIconButton(onClick = actions.playPause, modifier = Modifier.size(48.dp)) {
                        Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (state.playing) R.string.review_pause else R.string.review_play))
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = actions.cycleSpeed) { Text(stringResource(R.string.review_speed, state.speed.toString().removeSuffix(".0"))) }
                        IconButton(onClick = { noting = state.positionMillis }) {
                            Icon(Icons.Filled.EditNote, stringResource(R.string.review_note_here, formatClock(state.positionMillis)))
                        }
                    }
                }
            }
        }
    }
}

/** Lines in a row by one speaker, as one paragraph. */
private fun turnsOf(lines: List<Segment>): List<List<Segment>> =
    lines.fold(mutableListOf<MutableList<Segment>>()) { turns, line ->
        if (turns.isNotEmpty() && turns.last().last().speakerId == line.speakerId) turns.last() += line else turns += mutableListOf(line)
        turns
    }

@Composable
private fun Banner(text: String, progress: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

/** The color of a speaker, by order of speaking; the time color for an unknown speaker. */
@Composable
fun speakerColor(id: String?): Color {
    val palette = LocalTranscriptorColors.current.speakers
    if (id == null) return LocalTranscriptorColors.current.time
    return palette[((id.removePrefix("speaker-").toIntOrNull() ?: 1) - 1).mod(palette.size)]
}

/** A speaker's name in their color. */
@Composable
fun ReviewSpeakerChip(speaker: Speaker, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val color = speakerColor(speaker.id)
    Text(
        speakerLabel(speaker),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The whole lecture, in time order left to right: a lane per speaker with their turns, the notes above, the playhead. */
@Composable
private fun Overview(state: ReviewState, actions: ReviewActions) {
    val duration = state.lecture.durationMillis.coerceAtLeast(1)
    val speakers = state.lecture.speakers
    val laneColors = speakers.map { speakerColor(it.id) }
    val unknown = speakerColor(null)
    val noteColor = LocalTranscriptorColors.current.onNote
    val head = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val lanes = speakers.size.coerceAtLeast(1)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            Modifier.fillMaxWidth().height((14 + 10 * lanes).dp).padding(horizontal = 16.dp)
                .pointerInput(duration) { detectTapGestures { actions.seek((it.x / size.width * duration).toLong()) } },
        ) {
            val lane = 8.dp.toPx()
            val gap = 2.dp.toPx()
            val top = 8.dp.toPx()
            for (i in 0 until lanes) drawRect(track, Offset(0f, top + i * (lane + gap)), Size(size.width, lane))
            state.lecture.segments.forEach { line ->
                val i = speakers.indexOfFirst { it.id == line.speakerId }
                val y = top + i.coerceAtLeast(0) * (lane + gap)
                val x0 = line.startMillis.toFloat() / duration * size.width
                val x1 = (line.endMillis.toFloat() / duration * size.width).coerceAtLeast(x0 + 2f)
                drawRect(if (i >= 0) laneColors[i] else unknown, Offset(x0, y), Size(x1 - x0, lane))
            }
            state.lecture.notes.forEach { n -> drawCircle(noteColor, 3.dp.toPx(), Offset(n.atMillis.toFloat() / duration * size.width, 3.dp.toPx())) }
            val px = state.positionMillis.toFloat() / duration * size.width
            drawRect(head, Offset(px - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
    }
}

@Composable
private fun Turn(
    turn: List<Segment>, state: ReviewState, actions: ReviewActions, editing: Boolean, edits: MutableMap<String, String>, notes: List<Note>,
    onSpeaker: (Speaker) -> Unit, onMove: (Segment) -> Unit, onNote: (Note) -> Unit,
) {
    val speaker = state.lecture.speakers.firstOrNull { it.id == turn.first().speakerId }
    val colors = LocalTranscriptorColors.current
    val current = state.currentLine?.id
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            speaker?.let { ReviewSpeakerChip(it) { onSpeaker(it) } }
            Spacer(Modifier.width(8.dp))
            Text(formatClock(turn.first().startMillis), style = MaterialTheme.typography.labelSmall, color = colors.time)
        }
        if (editing) {
            turn.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = edits[line.id] ?: line.text, onValueChange = { edits[line.id] = it },
                        modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.lecture.speakers.size > 1) {
                        Box(Modifier.padding(start = 6.dp).size(14.dp).clip(RoundedCornerShape(7.dp)).background(speakerColor(line.speakerId)).clickable { onMove(line) })
                    }
                }
            }
        } else {
            val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            val text = buildAnnotatedString {
                turn.forEachIndexed { i, line ->
                    if (i > 0) append(" ")
                    withLink(LinkAnnotation.Clickable(line.id, TextLinkStyles(SpanStyle())) { actions.playLine(line) }) {
                        if (line.id == current) withStyle(SpanStyle(background = highlight)) { append(line.text) } else append(line.text)
                    }
                    if (line.edited) withStyle(SpanStyle(color = colors.time, fontSize = 11.sp)) { append(" ✎") }
                }
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp)
        }
        notes.forEach { n -> NotePill(n, onTime = { actions.seek(n.atMillis) }, onHold = { onNote(n) }) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotePill(note: Note, onTime: () -> Unit, onHold: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(colors.note).combinedClickable(onClick = { open = !open }, onLongClick = onHold)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.EditNote, null, tint = colors.onNote, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(formatClock(note.atMillis), style = MaterialTheme.typography.labelSmall, color = colors.onNote, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onTime))
        Spacer(Modifier.width(6.dp))
        Text(note.text, style = MaterialTheme.typography.bodySmall, color = colors.onNote, maxLines = if (open) 10 else 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MoveLineDialog(line: Segment, speakers: List<Speaker>, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_move_line, formatClock(line.startMillis))) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                speakers.forEach { s -> FilterChip(selected = line.speakerId == s.id, onClick = { onMove(s.id) }, label = { Text(speakerLabel(s), color = speakerColor(s.id)) }) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) } },
    )
}

@Composable
private fun NoteDialog(title: String, initial: String, onDelete: (() -> Unit)?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TextField(value = text, onValueChange = { text = it }, placeholder = { Text(stringResource(R.string.review_note_hint)) }, minLines = 2) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.review_save)) } },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.review_delete)) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) }
            }
        },
    )
}

/** The models the refine pass can use here; the large ones first. */
@Composable
private fun RefineDialog(state: ReviewState, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_refine_with)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.review_refine_note), style = MaterialTheme.typography.bodySmall)
                state.refineModels.forEach { m ->
                    TextButton(onClick = { onPick(m.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(m.name, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) } },
    )
}

/** The transcript now and the earlier ones, newest first: who wrote each, when it was kept, its lines and edits. */
@Composable
private fun VersionsDialog(state: ReviewState, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val lecture = state.lecture
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_versions, lecture.versions.size + 1)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val nowModel = lecture.refinedWith ?: lecture.segments.groupingBy { it.modelId }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                VersionRow(
                    stringResource(R.string.review_version_now),
                    stringResource(if (lecture.refinedWith != null) R.string.review_version_refined else R.string.review_version_live, com.chencohensolution.transcriptor.models.ModelCatalog.nameOf(nowModel)),
                    stringResource(R.string.review_version_lines, lecture.segments.size, lecture.segments.count { it.edited }),
                    current = true, onClick = null,
                )
                lecture.versions.sortedByDescending { it.keptAtMillis }.forEach { v ->
                    VersionRow(
                        format.format(java.util.Date(v.keptAtMillis)),
                        stringResource(if (v.refined) R.string.review_version_refined else R.string.review_version_live, com.chencohensolution.transcriptor.models.ModelCatalog.nameOf(v.modelId)),
                        stringResource(R.string.review_version_lines, v.lines, v.edited),
                        current = false, onClick = { onPick(v.id) },
                    )
                }
                Text(stringResource(R.string.review_versions_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) } },
    )
}

@Composable
private fun VersionRow(title: String, model: String, lines: String, current: Boolean, onClick: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(model, style = MaterialTheme.typography.bodyMedium)
        Text(lines, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
    }
}
