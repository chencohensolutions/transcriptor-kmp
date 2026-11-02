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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.transcriptor.R
import kotlinx.coroutines.launch

/**
 * The lecture while recording, as one timeline: lines and notes in the order they happened, notes as amber cards
 * among the lines. The note bar is always open at the bottom; the note's time is taken when typing starts.
 */
@Composable
fun LectureScreenA(state: LiveLectureState, actions: LiveLectureActions) {
    val colors = LocalTranscriptorColors.current
    val entries = timelineOf(state.lecture)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atLive by remember { derivedStateOf { !list.canScrollForward } }
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(atLive) { if (atLive) following = true }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress && !atLive) following = false }
    LaunchedEffect(entries.size, state.partial, following) { if (following) list.scrollToItem(entries.size + 1) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lecture_back))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(state.lecture.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.notebookTitle, style = MaterialTheme.typography.bodySmall, color = colors.time, maxLines = 1)
                    }
                    RecordingDot(state.paused)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.paused) stringResource(R.string.lecture_paused) else formatClock(state.elapsedMillis),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    LevelBars(state.level)
                    IconButton(onClick = actions.pauseOrResume) {
                        Icon(
                            if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            stringResource(if (state.paused) R.string.lecture_resume else R.string.lecture_pause),
                        )
                    }
                    FilledIconButton(
                        onClick = actions.stop,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.recording, contentColor = Color.White),
                    ) { Icon(Icons.Filled.Stop, stringResource(R.string.lecture_stop)) }
                }
                HorizontalDivider()
            }
        }

        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
                LazyColumn(state = list, contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.lecture_live_model, state.liveModelName, state.lagMillis / 1000f),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.time,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(entries, key = { if (it is TimelineEntry.Speech) it.segment.id else (it as TimelineEntry.Written).note.id }) { entry ->
                        when (entry) {
                            is TimelineEntry.Speech -> SpeechRowA(entry, onClick = { actions.openSegment(entry.segment) })
                            is TimelineEntry.Written -> NoteCardA(entry)
                        }
                    }
                    item {
                        if (state.partial != null) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(formatClock(state.lecture.segments.last().endMillis + 1500), style = MaterialTheme.typography.labelSmall, color = colors.time, modifier = Modifier.width(44.dp).padding(top = 3.dp))
                                Text(state.partial + " …", style = MaterialTheme.typography.bodyLarge, color = colors.partial, fontStyle = FontStyle.Italic)
                            }
                        }
                    }
                }
            }
            if (!following) {
                Surface(
                    onClick = { following = true; scope.launch { list.animateScrollToItem(entries.size + 1) } },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.lecture_follow_live), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        NoteBarA(state, actions)
    }
}

@Composable
private fun SpeechRowA(entry: TimelineEntry.Speech, onClick: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            formatClock(entry.segment.startMillis),
            style = MaterialTheme.typography.labelSmall,
            color = colors.time,
            modifier = Modifier.width(44.dp).padding(top = 3.dp),
        )
        Text(entry.segment.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
    }
}

@Composable
private fun NoteCardA(entry: TimelineEntry.Written) {
    val colors = LocalTranscriptorColors.current
    Row(Modifier.padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(colors.note).padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 520.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.EditNote, null, tint = colors.onNote, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(formatClock(entry.note.atMillis), style = MaterialTheme.typography.labelSmall, color = colors.onNote.copy(alpha = 0.7f))
                Text(entry.note.text, style = MaterialTheme.typography.bodyMedium, color = colors.onNote)
            }
        }
    }
}

@Composable
private fun NoteBarA(state: LiveLectureState, actions: LiveLectureActions) {
    val colors = LocalTranscriptorColors.current
    var text by remember { mutableStateOf("") }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(Modifier.navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = {
                    if (startedAt == null && it.isNotEmpty()) startedAt = state.elapsedMillis
                    if (it.isEmpty()) startedAt = null
                    text = it
                },
                placeholder = { Text(stringResource(R.string.lecture_note_hint)) },
                leadingIcon = {
                    val at = startedAt
                    if (at == null) Icon(Icons.Filled.EditNote, null)
                    else Text(
                        formatClock(at),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onNote,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.note).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = {
                    startedAt?.let { actions.addNote(it, text.trim()) }
                    text = ""
                    startedAt = null
                },
                enabled = text.isNotBlank(),
            ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.lecture_save_note)) }
        }
    }
}
