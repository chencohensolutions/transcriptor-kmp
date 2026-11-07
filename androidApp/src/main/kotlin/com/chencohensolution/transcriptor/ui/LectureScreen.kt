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
import androidx.compose.material.icons.filled.FiberManualRecord
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
import kotlinx.coroutines.delay
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable as FloatAnimatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.key
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle


class LiveLectureActions(
    val back: () -> Unit = {},
    val pauseOrResume: () -> Unit = {},
    val stop: () -> Unit = {},
    /** A note pinned to [atMillis], when the user started typing it. */
    val addNote: (atMillis: Long, text: String) -> Unit = { _, _ -> },
    /** The user's words for a finished line: kept over live transcription's, saved, marked edited. */
    val editLine: (id: String, text: String) -> Unit = { _, _ -> },
    /** Names a speaker ("Lecturer"); the notebook remembers the voice. */
    val renameSpeaker: (id: String, name: String) -> Unit = { _, _ -> },
)

/**
 * The lecture while recording, as one timeline: lines and notes in the order they happened, notes as amber cards
 * among the lines. The note bar is always open at the bottom; the note's time is taken when typing starts.
 *
 * Tapping a finished line turns it into a field where it is, with Cancel and Save (round 2's a2): it glides up to stay
 * above the keyboard as it opens and as the text grows; recording and live lines go on below, the note bar steps
 * aside, and a saved line is marked ✎ (edited: the improve pass keeps it). The line being spoken now can't be edited.
 *
 * The list is laid out from the bottom, so the live line stays in view by itself and the lines above glide up as it
 * grows and as new lines come, with no scrolling; each new word fades in quickly, and a finished line settles from
 * grey into the text's color.
 */
@Composable
fun LectureScreen(state: LiveLectureState, actions: LiveLectureActions) {
    var editingId by remember { mutableStateOf<String?>(null) }
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
    // The lines already there when the screen opened don't animate; every line after them is new.
    val shownAtOpen = remember { state.lecture.segments.map { it.id }.toSet() }
    renaming?.let { speaker ->
        RenameSpeakerDialog(speaker, onDismiss = { renaming = null }) { name ->
            actions.renameSpeaker(speaker.id, name)
            renaming = null
        }
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The list is laid out from the bottom (reversed): the live line is its first item and stays at the bottom by
    // itself, so following it needs no scrolling (a scroll running all the time would swallow taps). Lines above it
    // glide up as it grows and as new lines come in. Scrolled back, the lines in view stay put.
    val following by remember { derivedStateOf { !list.canScrollBackward } }

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
                LazyColumn(state = list, reverseLayout = true, contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = "live") {
                        // Grows with a glide as words come in, and shrinks as its line becomes a finished one.
                        Box(Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) { if (state.partial != null) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(formatClock(state.lecture.segments.lastOrNull()?.endMillis ?: 0), style = MaterialTheme.typography.labelSmall, color = colors.time, modifier = Modifier.width(44.dp).padding(top = 3.dp))
                                key(state.lecture.segments.size) { FadingWords(state.partial.orEmpty(), colors.partial) }
                            }
                        } }
                    }
                    items(entries.asReversed(), key = { if (it is TimelineEntry.Speech) it.segment.id else (it as TimelineEntry.Written).note.id }) { entry ->
                        when (entry) {
                            is TimelineEntry.Speech -> SpeechRow(
                                entry, fresh = entry.segment.id !in shownAtOpen,
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                                speaker = speakerChanges[entry.segment.id]?.let { id -> state.lecture.speakers.firstOrNull { it.id == id } },
                                onSpeaker = { renaming = it },
                                editing = entry.segment.id == editingId,
                                onClick = { editingId = entry.segment.id },
                                onCancel = { editingId = null },
                                onSave = { actions.editLine(entry.segment.id, it); editingId = null },
                            )
                            is TimelineEntry.Written -> Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = Spring.StiffnessMediumLow))) { NoteCard(entry) }
                        }
                    }
                    // The app's text, in the app's direction, among the notebook's content.
                    item(key = "status") {
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
                }
            }
            if (!following) {
                Surface(
                    onClick = { scope.launch { list.animateScrollToItem(0) } },
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

        if (editingId == null) NoteBar(state, actions)
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
private fun SpeechRow(
    entry: TimelineEntry.Speech, fresh: Boolean, modifier: Modifier, speaker: Speaker?, onSpeaker: (Speaker) -> Unit,
    editing: Boolean, onClick: () -> Unit, onCancel: () -> Unit, onSave: (String) -> Unit,
) {
    val colors = LocalTranscriptorColors.current
    // A new line settles quickly from the live line's grey into the text's color, where the live line was.
    val ink = MaterialTheme.colorScheme.onSurface
    val color = remember { Animatable(if (fresh) colors.partial else ink) }
    LaunchedEffect(ink) { color.animateTo(ink, tween(350)) }
    // While fixed, the line glides up to stay above the keyboard, as it opens and as the text grows.
    val inView = remember { BringIntoViewRequester() }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    Column(
        modifier.fillMaxWidth().bringIntoViewRequester(inView)
            .background(if (editing) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(enabled = !editing, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (speaker != null) SpeakerChip(speaker, Modifier.padding(start = 44.dp, bottom = 4.dp)) { onSpeaker(speaker) }
        Row {
            Column(Modifier.width(44.dp).padding(top = 3.dp)) {
                Text(formatClock(entry.segment.startMillis), style = MaterialTheme.typography.labelSmall, color = colors.time)
                if (entry.segment.edited) Text("✎", style = MaterialTheme.typography.labelSmall, color = colors.time)
            }
            if (!editing) {
                Text(entry.segment.text, style = MaterialTheme.typography.bodyLarge.inNotebookDirection(), lineHeight = 26.sp, color = color.value)
            } else {
                var text by remember { mutableStateOf(TextFieldValue(entry.segment.text, TextRange(entry.segment.text.length))) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                LaunchedEffect(keyboard, text.text.length / 20) { delay(60); inView.bringIntoView() }
                Column(Modifier.weight(1f)) {
                    TextField(
                        value = text, onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge.inNotebookDirection().copy(lineHeight = 26.sp),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    val appDirection = LocalLayoutDirection.current
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FiberManualRecord, null, tint = colors.recording, modifier = Modifier.size(10.dp))
                        Text(
                            stringResource(R.string.lecture_edit_still_live), style = MaterialTheme.typography.labelSmall, color = colors.time,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                        )
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.lecture_edit_cancel)) }
                        TextButton(onClick = { onSave(text.text) }, enabled = text.text.isNotBlank()) { Text(stringResource(R.string.lecture_edit_save)) }
                    }
                }
            }
        }
    }
}


/** "Speaker 2", or the name the user gave; its number is its order of speaking. */
@Composable
fun speakerLabel(speaker: Speaker): String =
    speaker.name.ifEmpty { stringResource(R.string.speaker_n, speaker.id.removePrefix("speaker-").toIntOrNull() ?: 0) }

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
internal fun RenameSpeakerDialog(speaker: Speaker, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(speaker.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speaker_rename_title, speakerLabel(speaker))) },
        text = {
            Column {
                Text(stringResource(R.string.speaker_rename_note), style = MaterialTheme.typography.bodySmall)
                TextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.speaker_rename_hint)) },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.speaker_rename_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.speaker_rename_cancel)) } },
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

/** The line being spoken, written in a word at a time, each word fading in over 200 ms; keyed by the caller per line. */
@Composable
private fun FadingWords(text: String, color: Color) {
    val words = text.split(" ")
    val alphas = remember { HashMap<Int, FloatAnimatable<Float, AnimationVector1D>>() }
    for (i in words.indices) alphas.getOrPut(i) { FloatAnimatable(0f) }
    LaunchedEffect(words.size) {
        for (i in words.indices) { val a = alphas.getValue(i); if (a.targetValue < 1f) launch { a.animateTo(1f, tween(200)) } }
    }
    val shown = buildAnnotatedString {
        words.forEachIndexed { i, w ->
            if (i > 0) append(" ")
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * (alphas[i]?.value ?: 1f)))) { append(w) }
        }
    }
    Text(shown, style = MaterialTheme.typography.bodyLarge.inNotebookDirection(), lineHeight = 26.sp, fontStyle = FontStyle.Italic)
}
