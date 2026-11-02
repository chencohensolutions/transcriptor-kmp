package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.transcriptor.R
import kotlinx.coroutines.launch

/**
 * The lecture while recording, in two panes: the live transcript on top, the user's notes below as a page of their
 * own. Each line typed in the notes pane is a note with the time its typing started; Enter starts the next one.
 * Tapping a note's time scrolls the transcript there. The divider drags.
 */
@Composable
fun LectureScreenB(state: LiveLectureState, actions: LiveLectureActions) {
    val colors = LocalTranscriptorColors.current
    var split by remember { mutableFloatStateOf(0.58f) }
    val transcript = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val segments = state.lecture.segments
    LaunchedEffect(segments.size, state.partial) { transcript.scrollToItem(segments.size) }
    val direction = layoutDirectionOf(state.lecture.language)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lecture_back)) }
                Column(Modifier.weight(1f)) {
                    Text(state.lecture.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RecordingDot(state.paused, Modifier.size(8.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.paused) stringResource(R.string.lecture_paused) else formatClock(state.elapsedMillis),
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        LevelBars(state.level, height = 12)
                    }
                }
                IconButton(onClick = actions.pauseOrResume) {
                    Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(if (state.paused) R.string.lecture_resume else R.string.lecture_pause))
                }
                FilledIconButton(onClick = actions.stop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.recording, contentColor = Color.White)) {
                    Icon(Icons.Filled.Stop, stringResource(R.string.lecture_stop))
                }
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            LazyColumn(state = transcript, contentPadding = PaddingValues(vertical = 10.dp), modifier = Modifier.weight(split).fillMaxWidth()) {
                itemsIndexed(segments, key = { _, it -> it.id }) { _, seg ->
                    Row(Modifier.fillMaxWidth().clickable { actions.openSegment(seg) }.padding(horizontal = 16.dp, vertical = 5.dp)) {
                        Text(formatClock(seg.startMillis), style = MaterialTheme.typography.labelSmall, color = colors.time, modifier = Modifier.width(44.dp).padding(top = 3.dp))
                        Text(seg.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                    }
                }
                item {
                    state.partial?.let {
                        Text(it + " …", style = MaterialTheme.typography.bodyLarge, color = colors.partial, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 60.dp, end = 16.dp, top = 5.dp))
                    }
                }
            }
        }

        // The divider: drag to give either pane more room.
        Box(
            Modifier.fillMaxWidth().height(22.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> split = (split + dy / size.height * 0.04f).coerceIn(0.25f, 0.8f) } },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.lecture_b_notes), style = MaterialTheme.typography.labelMedium, color = colors.time)
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.time.copy(alpha = 0.5f)))
                Text(stringResource(R.string.lecture_b_count, state.lecture.notes.size), style = MaterialTheme.typography.labelMedium, color = colors.time)
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            NotesPageB(
                state, actions,
                onJump = { at ->
                    val index = segments.indexOfLast { it.startMillis <= at }.coerceAtLeast(0)
                    scope.launch { transcript.animateScrollToItem(index) }
                },
                modifier = Modifier.weight(1f - split).fillMaxWidth().background(colors.note.copy(alpha = 0.35f)),
            )
        }
    }
}

@Composable
private fun NotesPageB(state: LiveLectureState, actions: LiveLectureActions, onJump: (Long) -> Unit, modifier: Modifier) {
    val colors = LocalTranscriptorColors.current
    var draft by remember { mutableStateOf("") }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(state.lecture.notes.size) { list.scrollToItem(state.lecture.notes.size) }
    fun commit() {
        val at = startedAt
        if (at != null && draft.isNotBlank()) actions.addNote(at, draft.trim())
        draft = ""
        startedAt = null
    }
    LazyColumn(state = list, modifier = modifier.navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(state.lecture.notes, key = { _, it -> it.id }) { _, note ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                Text(
                    formatClock(note.atMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onNote,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.note).clickable { onJump(note.atMillis) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(note.text, style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.note)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    startedAt?.let(::formatClock) ?: formatClock(state.elapsedMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (startedAt != null) colors.onNote else colors.time,
                    modifier = Modifier.padding(top = 2.dp, end = 10.dp),
                )
                Box(Modifier.weight(1f)) {
                    if (draft.isEmpty()) Text(stringResource(R.string.lecture_b_hint), style = MaterialTheme.typography.bodyLarge, color = colors.partial)
                    BasicTextField(
                        value = draft,
                        onValueChange = { v ->
                            if (v.endsWith("\n")) { draft = v.trimEnd('\n'); commit(); return@BasicTextField }
                            if (startedAt == null && v.isNotEmpty()) startedAt = state.elapsedMillis
                            if (v.isEmpty()) startedAt = null
                            draft = v
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { commit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
