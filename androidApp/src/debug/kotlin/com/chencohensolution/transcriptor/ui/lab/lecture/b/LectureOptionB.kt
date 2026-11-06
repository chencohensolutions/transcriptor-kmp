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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.chencohensolution.transcriptor.notebook.TimelineEntry
import com.chencohensolution.transcriptor.notebook.timelineOf
import com.chencohensolution.transcriptor.recording.LiveLectureState
import com.chencohensolution.transcriptor.recording.ModelInUse
import kotlinx.coroutines.launch


/**
 * Option b, fix lines in a sheet: the app's one timeline; tapping a finished line opens a sheet over the bottom half
 * with that line in a field, its time and speaker, and ‹ › to step to the line before or after (each change saved as
 * you go). On top of the sheet, what is being said now, so the lecture stays in view; behind it the timeline keeps
 * growing and following. A saved line is marked ✎ (edited: the improve pass keeps it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectureScreenB(state: LiveLectureState, actions: LiveLectureActions, editLine: (id: String, text: String) -> Unit) {
    var editingId by remember { mutableStateOf<String?>(null) }
    editingId?.let { id ->
        val segments = state.lecture.segments
        val index = segments.indexOfFirst { it.id == id }
        if (index >= 0) EditLineSheet(
            state, segments[index], hasPrevious = index > 0, hasNext = index < segments.lastIndex,
            onDismiss = { text -> editLine(id, text); editingId = null },
            onStep = { text, by -> editLine(id, text); editingId = segments[index + by].id },
        )
    }
    val colors = LocalTranscriptorColors.current
    val entries = remember(state.lecture.segments, state.lecture.notes) { timelineOf(state.lecture) }
    // A line shows its speaker where the speaker changes: line id to speaker id.
    val speakerChanges = remember(state.lecture.segments) {
        var previous: String? = null
        buildMap {
            for (s in state.lecture.segments) {
                if (s.speakerId != null && s.speakerId != previous) put(s.id, s.speakerId!!)
                if (s.speakerId != null) previous = s.speakerId
            }
        }
    }
    var renaming by remember { mutableStateOf<Speaker?>(null) }
    renaming?.let { speaker ->
        RenameSpeakerDialog(speaker, onDismiss = { renaming = null }) { name ->
            actions.renameSpeaker(speaker.id, name)
            renaming = null
        }
    }
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
                        Text(state.lecture.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = titleDirection(state.lecture.language)), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    IconButton(onClick = actions.pauseOrResume, enabled = !state.finishing) {
                        Icon(
                            if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            stringResource(if (state.paused) R.string.lecture_resume else R.string.lecture_pause),
                        )
                    }
                    FilledIconButton(
                        onClick = actions.stop,
                        enabled = !state.finishing,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.recording, contentColor = Color.White),
                    ) { Icon(Icons.Filled.Stop, stringResource(R.string.lecture_stop)) }
                }
                HorizontalDivider()
            }
        }

        Box(Modifier.weight(1f)) {
            val appDirection = LocalLayoutDirection.current
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
                LazyColumn(state = list, contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
                    // The app's text, in the app's direction, among the notebook's content.
                    item {
                        val model = state.liveModelName
                        val error = state.liveError
                        val status = when {
                            state.finishing -> stringResource(R.string.lecture_finishing)
                            model == null -> stringResource(R.string.lecture_no_live_model)
                            error != null -> stringResource(R.string.lecture_live_failed, error)
                            state.liveLoading -> stringResource(R.string.lecture_live_loading, model)
                            else -> stringResource(R.string.lecture_live_model, model, (state.lagMillis ?: 0) / 1000f)
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides appDirection) { Column {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.liveError != null) MaterialTheme.colorScheme.error else colors.time,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            if (!state.finishing) ModelsInUse(state)
                        } }
                    }
                    items(entries, key = { if (it is TimelineEntry.Speech) it.segment.id else (it as TimelineEntry.Written).note.id }) { entry ->
                        when (entry) {
                            is TimelineEntry.Speech -> SpeechRow(
                                entry,
                                speaker = speakerChanges[entry.segment.id]?.let { id -> state.lecture.speakers.firstOrNull { it.id == id } },
                                onSpeaker = { renaming = it },
                                onClick = { editingId = entry.segment.id },
                            )
                            is TimelineEntry.Written -> NoteCard(entry)
                        }
                    }
                    item {
                        if (state.partial != null) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(formatClock(state.lecture.segments.lastOrNull()?.endMillis ?: 0), style = MaterialTheme.typography.labelSmall, color = colors.time, modifier = Modifier.width(44.dp).padding(top = 3.dp))
                                Text(state.partial + " …", style = MaterialTheme.typography.bodyLarge.inNotebookDirection(), color = colors.partial, fontStyle = FontStyle.Italic)
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

        NoteBar(state, actions)
    }
}

/**
 * The notebook's text as paragraphs in the notebook's direction (the layout direction provided around it), not
 * guessed from the first letter: a Hebrew line that starts with an English word or a number stays right-to-left.
 */
@Composable
private fun TextStyle.inNotebookDirection() =
    copy(textDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr)

@Composable
private fun SpeechRow(entry: TimelineEntry.Speech, speaker: Speaker?, onSpeaker: (Speaker) -> Unit, onClick: () -> Unit) {
    val colors = LocalTranscriptorColors.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (speaker != null) SpeakerChip(speaker, Modifier.padding(start = 44.dp, bottom = 4.dp)) { onSpeaker(speaker) }
        Row {
            Column(Modifier.width(44.dp).padding(top = 3.dp)) {
                Text(formatClock(entry.segment.startMillis), style = MaterialTheme.typography.labelSmall, color = colors.time)
                if (entry.segment.edited) Text("✎", style = MaterialTheme.typography.labelSmall, color = colors.time)
            }
            Text(entry.segment.text, style = MaterialTheme.typography.bodyLarge.inNotebookDirection(), lineHeight = 26.sp)
        }
    }
}


/** The speaker's name in their color; tap to rename. */
@Composable
private fun SpeakerChip(speaker: Speaker, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalTranscriptorColors.current.speakers
    val color = palette[((speaker.id.removePrefix("speaker-").toIntOrNull() ?: 1) - 1).mod(palette.size)]
    Text(
        speakerLabel(speaker),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}


@Composable
private fun NoteCard(entry: TimelineEntry.Written) {
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
                Text(entry.note.text, style = MaterialTheme.typography.bodyMedium.inNotebookDirection(), color = colors.onNote)
            }
        }
    }
}

@Composable
private fun NoteBar(state: LiveLectureState, actions: LiveLectureActions) {
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

/**
 * The other models the recording uses, under the live model's line: the voice-print model and where it runs (the NPU,
 * the CPU, or the CPU because its NPU build failed), and the speaker pass after Stop.
 */
@Composable
private fun ModelsInUse(state: LiveLectureState) {
    val prints = state.voicePrints ?: return
    val runs = when (prints.runs) {
        ModelInUse.Runs.NotYet -> stringResource(R.string.lecture_model_first_line)
        ModelInUse.Runs.Npu -> stringResource(R.string.lecture_model_npu)
        ModelInUse.Runs.Cpu -> stringResource(R.string.lecture_model_cpu)
        ModelInUse.Runs.CpuAfterNpuFailed -> stringResource(R.string.lecture_model_npu_failed)
    }
    val lines = listOfNotNull(
        stringResource(if (state.speakersLive) R.string.lecture_speakers_live else R.string.lecture_speakers_model, isolated(prints.name), runs),
        if (state.speakerPassOnStop) stringResource(R.string.lecture_speaker_pass_on_stop, isolated(prints.name)) else null,
    )
    for (line in lines) {
        Text(
            line,
            style = MaterialTheme.typography.labelSmall,
            color = if (prints.runs == ModelInUse.Runs.CpuAfterNpuFailed) MaterialTheme.colorScheme.error else LocalTranscriptorColors.current.time,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

/**
 * The line in a field, with its time and speaker, ‹ › to the line before and after, and what is being said now above
 * it. Closing the sheet saves the line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditLineSheet(
    state: LiveLectureState, line: Segment, hasPrevious: Boolean, hasNext: Boolean,
    onDismiss: (String) -> Unit, onStep: (text: String, by: Int) -> Unit,
) {
    val colors = LocalTranscriptorColors.current
    var text by remember(line.id) { mutableStateOf(TextFieldValue(line.text, TextRange(line.text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(line.id) { focus.requestFocus() }
    ModalBottomSheet(onDismissRequest = { onDismiss(text.text) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingDot(state.paused)
                Text(
                    state.partial?.let { "$it …" } ?: state.lecture.segments.lastOrNull()?.text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = titleDirection(state.lecture.language)),
                    color = colors.partial, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.lecture_b_line_at, formatClock(line.startMillis)),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onStep(text.text, -1) }, enabled = hasPrevious) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.lecture_b_previous))
                }
                IconButton(onClick = { onStep(text.text, 1) }, enabled = hasNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.lecture_b_next))
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
                TextField(
                    value = text, onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.inNotebookDirection().copy(lineHeight = 26.sp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).focusRequester(focus),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { onDismiss(text.text) }, enabled = text.text.isNotBlank()) { Text(stringResource(R.string.lecture_b_done)) }
            }
        }
    }
}
