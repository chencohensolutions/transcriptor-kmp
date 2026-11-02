package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

/**
 * The lecture after recording as a document: an overview strip of the whole lecture (a lane per speaker, the notes,
 * the playhead; tap to jump) over paragraphs, one per speaker turn, that read like text. Tap a sentence to play it;
 * the pencil turns the paragraphs into fields to correct. Notes are pills after the paragraph they were written in;
 * the passes are in the menu, with a progress banner while one runs.
 */
@Composable
fun ReviewScreenC(state: ReviewState, actions: ReviewActions) {
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val turns = remember(state.lecture.segments) { turnsOf(state.lecture.segments) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) }
                    Text(state.lecture.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editing = !editing }) {
                        Icon(if (editing) Icons.Filled.Check else Icons.Filled.Edit, stringResource(if (editing) R.string.review_c_done else R.string.review_c_edit))
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.review_refine, state.refineModelName)) }, enabled = state.refine !is PassState.Running,
                            onClick = { actions.refine(); menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.review_find_speakers)) }, enabled = state.speakerPass !is PassState.Running,
                            onClick = { actions.findSpeakers(); menu = false })
                    }
                }
                OverviewC(state, actions)
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.playPause) { Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (state.playing) R.string.review_pause else R.string.review_play)) }
                    Text("${formatClock(state.positionMillis)} / ${formatClock(state.lecture.durationMillis)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.cycleSpeed) { Text(stringResource(R.string.review_speed, state.speed.toString().removeSuffix(".0"))) }
                }
                (state.refine as? PassState.Running)?.let { Banner(stringResource(R.string.review_refining, "large-v3", (it.progress * 100).toInt()), it.progress) }
                (state.speakerPass as? PassState.Running)?.let { Banner(stringResource(R.string.review_finding_speakers, (it.progress * 100).toInt()), it.progress) }
                HorizontalDivider()
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.weight(1f).navigationBarsPadding()) {
                items(turns, key = { it.first().id }) { turn ->
                    val notes = state.lecture.notes.filter { n -> n.atMillis >= turn.first().startMillis && n.atMillis < (turns.getOrNull(turns.indexOf(turn) + 1)?.first()?.startMillis ?: Long.MAX_VALUE) }
                    TurnC(turn, state, actions, editing, notes)
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

/** The whole lecture: a lane per speaker with their turns, the notes above, the playhead; tap to jump there. */
@Composable
private fun OverviewC(state: ReviewState, actions: ReviewActions) {
    val duration = state.lecture.durationMillis.coerceAtLeast(1)
    val speakers = state.lecture.speakers
    val laneColors = speakers.map { speakerColor(it.id) }
    val noteColor = LocalTranscriptorColors.current.onNote
    val head = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            Modifier.fillMaxWidth().height((14 + 10 * speakers.size.coerceAtLeast(1)).dp).padding(horizontal = 16.dp)
                .pointerInput(duration) { detectTapGestures { actions.seek((it.x / size.width * duration).toLong()) } },
        ) {
            val lane = 8.dp.toPx()
            val gap = 2.dp.toPx()
            val top = 8.dp.toPx()
            speakers.forEachIndexed { i, s ->
                val y = top + i * (lane + gap)
                drawRect(track, Offset(0f, y), Size(size.width, lane))
                state.lecture.segments.filter { it.speakerId == s.id }.forEach { line ->
                    val x0 = line.startMillis.toFloat() / duration * size.width
                    val x1 = (line.endMillis.toFloat() / duration * size.width).coerceAtLeast(x0 + 2f)
                    drawRect(laneColors[i], Offset(x0, y), Size(x1 - x0, lane))
                }
            }
            state.lecture.notes.forEach { n -> drawCircle(noteColor, 3.dp.toPx(), Offset(n.atMillis.toFloat() / duration * size.width, 3.dp.toPx())) }
            val px = state.positionMillis.toFloat() / duration * size.width
            drawRect(head, Offset(px - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
    }
}

@Composable
private fun TurnC(turn: List<Segment>, state: ReviewState, actions: ReviewActions, editing: Boolean, notes: List<Note>) {
    val speaker = state.lecture.speakers.firstOrNull { it.id == turn.first().speakerId }
    val colors = LocalTranscriptorColors.current
    val current = state.currentLine?.id
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            speaker?.let { ReviewSpeakerChip(it) }
            Spacer(Modifier.width(8.dp))
            Text(formatClock(turn.first().startMillis), style = MaterialTheme.typography.labelSmall, color = colors.time)
        }
        if (editing) {
            turn.forEach { line ->
                var text by remember(line.id, line.text) { mutableStateOf(line.text) }
                TextField(value = text, onValueChange = { text = it; actions.editLine(line.id, it) }, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge)
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
        notes.forEach { note -> NotePillC(note) { actions.seek(note.atMillis) } }
    }
}

@Composable
private fun NotePillC(note: Note, onTime: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(colors.note).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp),
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
