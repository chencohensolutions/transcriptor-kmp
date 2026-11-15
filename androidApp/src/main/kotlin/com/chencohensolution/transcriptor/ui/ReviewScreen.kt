package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import com.chencohensolution.transcriptor.review.LectureReview
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.chencohensolution.transcriptor.notebook.Label
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.summary
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.review.PassState
import com.chencohensolution.transcriptor.review.ReviewState

/** A model's name by its id: the catalog's and those added from Hugging Face (the app provides it). */
val LocalModelNames = androidx.compose.runtime.staticCompositionLocalOf<(String) -> String> { com.chencohensolution.transcriptor.models.ModelCatalog::nameOf }

class ReviewActions(
    val back: () -> Unit = {},
    /** The moment's details for its Info sheet; reads files, called off the main thread. */
    val info: () -> com.chencohensolution.transcriptor.review.MomentInfo? = { null },
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
    /** "Who was here?": names a speaker; a name someone here already has merges the two. */
    val namePerson: (id: String, name: String) -> Unit = { _, _ -> },
    /** Two speakers were one person: [from]'s lines become [into]'s. */
    val mergeSpeakers: (from: String, into: String) -> Unit = { _, _ -> },
    /** Plays a speaker's sample, or pauses it. */
    val playPerson: (id: String) -> Unit = {},
    /** Keeps playback to one speaker's turns; null, or the same one again: everyone. */
    val playOnly: (speakerId: String?) -> Unit = {},
    /** Opens Android's share sheet with the moment as plain text. */
    val shareText: () -> Unit = {},
    /** The moment's title, in the user's words. */
    val rename: (title: String) -> Unit = {},
    /** The moment's labels (ids), set from its screen. */
    val setLabels: (labels: List<String>) -> Unit = {},
    /** Makes a label; returns its id. */
    val newLabel: (name: String) -> String = { "" },
    val renameLabel: (labelId: String, name: String) -> Unit = { _, _ -> },
    /** Deletes a label everywhere: its moments stay, without it. */
    val deleteLabel: (labelId: String) -> Unit = {},
    /**
     * Improves the lecture with a model (a [com.chencohensolution.transcriptor.review.ModelChoice]'s id), after the
     * speaker pass when [speakersFirst].
     */
    val improve: (modelId: String, speakersFirst: Boolean) -> Unit = { _, _ -> },
    /** Downloads the model to get ([ReviewState.improveToGet]). */
    val getModel: (modelId: String) -> Unit = {},
    /** Makes an earlier transcript (a version's id) the current one, keeping this one as a version. */
    val switchVersion: (id: String) -> Unit = {},
    val findSpeakers: () -> Unit = {},
    /** "Tune on this lecture": the proposal by the user's corrections ([LectureReview.tuneSpeakers]); null: none. */
    val tuneSpeakers: () -> LectureReview.TuneProposal? = { null },
    val applySpeakerTuning: (LectureReview.TuneProposal) -> Unit = {},
    val addNote: (atMillis: Long, text: String) -> Unit = { _, _ -> },
    val editNote: (id: String, text: String) -> Unit = { _, _ -> },
    val deleteNote: (id: String) -> Unit = {},
    /** Makes the lecture's Word file and opens Android's share sheet with it. */
    val shareWord: () -> Unit = {},
    /** Saves the lecture's Word file in its folder, beside its recording. */
    val saveWord: () -> Unit = {},
    /** Deletes the lecture for good (asked first) and goes back to the list. */
    val deleteLecture: () -> Unit = {},
)

/**
 * A recorded lecture, as a document: paragraphs, one per speaker turn, that read like text; tap a sentence to play it,
 * a speaker's chip to name them. The pencil turns the paragraphs into fields (each line with its speaker, to move it)
 * that are saved on Done. Notes are pills after the paragraph they were written in (tap the time to jump, hold to edit
 * or delete). At the bottom, the overview of the whole lecture (a lane per speaker, the notes, the playhead; tap to
 * jump) and the player. The passes over the recording are in the menu, with a progress banner while one runs:
 * Improve transcription… is always there, whether or not lectures are improved on their own.
 *
 * Beside the pencil, Share (round 3's b): one tap makes the Word file with everything in it (the speakers' names, the
 * times, the notes) and opens Android's share sheet; holding it, or ⋮ → Save as Word, saves it in the lecture's folder.
 * Delete lecture…, in red at the end of ⋮, asks first, then goes back to the list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(
    state: ReviewState, actions: ReviewActions,
    askPeople: Boolean = false,
    /** Every label there is, to set this moment's from its own screen; empty hides nothing: "Add labels" makes the first. */
    allLabels: List<Label> = emptyList(),
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        icon = { Icon(Icons.Filled.Delete, null) },
        title = { Text(stringResource(R.string.review_delete_title, isolated(state.lecture.title))) },
        text = { Text(stringResource(R.string.review_delete_text)) },
        confirmButton = {
            TextButton(onClick = { confirmingDelete = false; actions.deleteLecture() }) {
                Text(stringResource(R.string.review_delete_lecture_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.review_delete_cancel)) } },
    )

    var editing by remember { mutableStateOf(false) }
    val edits = remember { mutableStateMapOf<String, String>() }
    var menu by remember { mutableStateOf(false) }
    var showingInfo by remember { mutableStateOf(false) }
    if (showingInfo) {
        val info by androidx.compose.runtime.produceState<com.chencohensolution.transcriptor.review.MomentInfo?>(null) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { actions.info() }
        }
        info?.let { MomentInfoSheet(it) { showingInfo = false } }
    }
    var renaming by remember { mutableStateOf<Speaker?>(null) }
    var moving by remember { mutableStateOf<Segment?>(null) }
    var note by remember { mutableStateOf<Note?>(null) }
    var noting by remember { mutableStateOf<Long?>(null) }
    var pickingModel by remember { mutableStateOf(false) }
    var showingVersions by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    // "Who was here?": from its button, and by itself once for a moment just recorded with voices still unnamed.
    var showingPeople by remember(state.lecture.id) { mutableStateOf(askPeople && state.people.size >= 2 && state.people.any { it.name.isEmpty() }) }
    if (showingPeople) PeopleSheet(state, actions, onDismiss = { showingPeople = false })
    var renamingTitle by remember { mutableStateOf(false) }
    var labeling by remember { mutableStateOf(false) }
    if (renamingTitle) RenameTitleDialog(state.lecture.title, onDismiss = { renamingTitle = false }) { actions.rename(it); renamingTitle = false }
    if (labeling) LabelsSheet(
        labels = allLabels, moments = listOf(state.lecture.summary()), onDismiss = { labeling = false },
        onSet = { add, remove -> actions.setLabels((state.lecture.labels + add).distinct() - remove) },
        onNew = { name -> actions.newLabel(name).takeIf { it.isNotEmpty() }?.let { actions.setLabels((state.lecture.labels + it).distinct()) } },
        onRename = actions.renameLabel, onDelete = actions.deleteLabel,
    )
    var tuning by remember { mutableStateOf<LectureReview.TuneProposal?>(null) }
    var noTuning by remember { mutableStateOf(false) }
    tuning?.let { p -> TuneDialog(p, onDismiss = { tuning = null }) { actions.applySpeakerTuning(p); tuning = null } }
    if (noTuning) AlertDialog(
        onDismissRequest = { noTuning = false }, confirmButton = { TextButton(onClick = { noTuning = false }) { Text(stringResource(R.string.review_tune_ok)) } },
        text = { Text(stringResource(R.string.review_tune_none)) },
    )
    val turns = remember(state.lecture.segments) { turnsOf(state.lecture.segments) }
    // Only recorded so far: no lines yet, a card says what it waits for.
    val waiting = state.lecture.pendingTranscript && state.lecture.segments.isEmpty()
    // The app's own text goes in the phone's direction, among the moment's content.
    val appDirection = LocalLayoutDirection.current

    renaming?.let { s -> RenameSpeakerDialog(s, onDismiss = { renaming = null }) { actions.renameSpeaker(s.id, it); renaming = null } }
    moving?.let { line -> MoveLineDialog(line, state.lecture.speakers, onDismiss = { moving = null }) { actions.setLineSpeaker(line.id, it); moving = null } }
    note?.let { n -> NoteDialog(stringResource(R.string.review_note_here, formatClock(n.atMillis)), n.text, onDelete = { actions.deleteNote(n.id); note = null }, onDismiss = { note = null }) { actions.editNote(n.id, it); note = null } }
    if (pickingModel) ImproveSheet(state, actions, onDismiss = { pickingModel = false })
    if (showingVersions) VersionsDialog(state, onDismiss = { showingVersions = false }) { actions.switchVersion(it); showingVersions = false }
    noting?.let { at -> NoteDialog(stringResource(R.string.review_note_here, formatClock(at)), "", onDelete = null, onDismiss = { noting = null }) { actions.addNote(at, it); noting = null } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) }
                    // The title renames the moment, the line under it sets its labels.
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.lecture.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = titleDirection(state.lecture.language)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = stringResource(R.string.review_rename)) { renamingTitle = true },
                        )
                        Text(
                            state.labelNames.ifEmpty { stringResource(R.string.review_add_labels) }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (state.labelNames.isEmpty()) MaterialTheme.colorScheme.primary else LocalTranscriptorColors.current.time,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = stringResource(R.string.notebooks_labels)) { labeling = true },
                        )
                    }
                    if (state.people.isNotEmpty()) IconButton(onClick = { showingPeople = true }) { Icon(Icons.Filled.Group, stringResource(R.string.people_title)) }
                    // Share: as text (to paste in a chat) or as a Word file; holding it saves the Word file beside the recording.
                    Box {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).combinedClickable(onClick = { sharing = true }, onLongClick = actions.saveWord),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Share, stringResource(R.string.review_share)) }
                        DropdownMenu(expanded = sharing, onDismissRequest = { sharing = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.review_share_text)) }, onClick = { sharing = false; actions.shareText() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.review_share_word)) }, onClick = { sharing = false; actions.shareWord() })
                        }
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
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_improve)) }, enabled = state.refine !is PassState.Running,
                                onClick = { pickingModel = true; menu = false },
                            )
                            if (state.lecture.versions.isNotEmpty()) DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_versions, state.lecture.versions.size + 1)) },
                                enabled = state.refine !is PassState.Running,
                                onClick = { showingVersions = true; menu = false },
                            )
                            state.speakerCorrections?.let { n ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.review_tune_speakers, n)) }, enabled = n > 0,
                                    onClick = { menu = false; actions.tuneSpeakers()?.let { tuning = it } ?: run { noTuning = true } },
                                )
                            }
                            if (state.speakerPass != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_find_speakers)) }, enabled = state.speakerPass !is PassState.Running,
                                onClick = { actions.findSpeakers(); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_save_word)) },
                                leadingIcon = { Icon(Icons.Filled.Description, null) },
                                onClick = { menu = false; actions.saveWord() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_info)) },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = { menu = false; showingInfo = true },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_delete_lecture), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmingDelete = true },
                            )
                        }
                    }
                }
                (state.refine as? PassState.Running)?.let {
                    Banner(stringResource(if (waiting) R.string.review_transcribing else R.string.review_improving, (it.progress * 100).toInt()), it.progress)
                }
                (state.speakerPass as? PassState.Running)?.let { Banner(stringResource(R.string.review_finding_speakers, (it.progress * 100).toInt()), it.progress) }
                // Where the transcript stands, with the next step: a live draft the better model hasn't written yet.
                if (state.refine == PassState.NotRun && state.lecture.refinedWith == null && state.lecture.segments.isNotEmpty() && state.refineModels.isNotEmpty()) {
                    val minutes = (state.refineModels.firstOrNull { it.id == state.improveDefault } ?: state.refineModels.first()).minutes
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.review_draft), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { pickingModel = true }) {
                            Text(if (minutes != null) stringResource(R.string.review_draft_improve_minutes, minutes) else stringResource(R.string.review_draft_improve))
                        }
                    }
                }
                state.power?.let { PowerLine(it) }
                HorizontalDivider()
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionOf(state.lecture.language)) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
                if (waiting) {
                    item(key = "waiting") { CompositionLocalProvider(LocalLayoutDirection provides appDirection) { NotTranscribedYet(state, actions) } }
                    // Its notes, which have no lines to sit under yet.
                    items(state.lecture.notes, key = { "note-${it.id}" }) { n -> NotePill(n, onTime = { actions.seek(n.atMillis) }, onHold = { note = n }) }
                }
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
                // Tap a person to hear only their turns; again for everyone.
                val people = state.people
                if (people.size >= 2) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(people, key = { it.speakerId }) { p ->
                        val color = speakerColor(p.speakerId)
                        FilterChip(
                            selected = state.onlySpeaker == p.speakerId, onClick = { actions.playOnly(p.speakerId) },
                            label = { Text(speakerLabel(Speaker(p.speakerId, p.name)), maxLines = 1) },
                            leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(color)) },
                        )
                    }
                }
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

/**
 * A moment that was only recorded: what it waits for, and the way to transcribe it now with its language's improve
 * model, or to get one.
 */
@Composable
private fun NotTranscribedYet(state: ReviewState, actions: ReviewActions) {
    val running = state.refine is PassState.Running
    val model = state.refineModels.firstOrNull { it.id == state.improveDefault } ?: state.refineModels.firstOrNull()
    val toGet = state.improveToGet
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (running) R.string.review_waiting_running_title else R.string.review_waiting_title), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    running -> stringResource(R.string.review_waiting_running)
                    model != null -> stringResource(R.string.review_waiting_note)
                    toGet != null -> stringResource(R.string.review_waiting_no_model)
                    else -> stringResource(R.string.review_waiting_no_model_settings)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!running && model != null) {
                Button(onClick = { actions.improve(model.id, false) }) {
                    Text(model.minutes?.let { stringResource(R.string.review_transcribe_now_minutes, it) } ?: stringResource(R.string.review_transcribe_now))
                }
                Text(isolated(model.name), style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time)
            } else if (!running && toGet != null) {
                Button(onClick = { actions.getModel(toGet.id) }) {
                    Text(stringResource(R.string.review_waiting_get, isolated(toGet.name), android.text.format.Formatter.formatShortFileSize(LocalContext.current, toGet.sizeBytes)))
                }
            }
        }
    }
}

/** A turn up to this long is drawn compact: the name and the words together. */
private const val COMPACT_TURN_CHARS = 140

/** The moment's title, to change. */
@Composable
private fun RenameTitleDialog(title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_rename)) },
        text = { TextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.review_rename_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_delete_cancel)) } },
    )
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
                val faded = state.onlySpeaker != null && line.speakerId != state.onlySpeaker
                drawRect((if (i >= 0) laneColors[i] else unknown).copy(alpha = if (faded) 0.25f else 1f), Offset(x0, y), Size(x1 - x0, lane))
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
    // A short turn (most of a conversation) is one piece: the name in its color, then the words, with no row of its
    // own for the name. A long one keeps the name and time above its paragraph.
    val compact = !editing && speaker != null && turn.sumOf { it.text.length } <= COMPACT_TURN_CHARS
    val faded = state.onlySpeaker != null && turn.first().speakerId != state.onlySpeaker
    Column(Modifier.alpha(if (faded) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!compact) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
            val nameColor = speakerColor(speaker?.id)
            val name = speaker?.let { speakerLabel(it) }.orEmpty()
            val text = buildAnnotatedString {
                if (compact && speaker != null) {
                    withLink(LinkAnnotation.Clickable("speaker", TextLinkStyles(SpanStyle(color = nameColor, fontWeight = FontWeight.SemiBold))) { onSpeaker(speaker) }) { append(isolated(name)) }
                    withStyle(SpanStyle(color = colors.time, fontSize = 11.sp)) { append("  " + formatClock(turn.first().startMillis) + "  ") }
                }
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
        // A mark (a note without words) shows its flag; holding it gives it words.
        Icon(if (note.isMark) Icons.Filled.Flag else Icons.Filled.EditNote, null, tint = colors.onNote, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(formatClock(note.atMillis), style = MaterialTheme.typography.labelSmall, color = colors.onNote, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onTime))
        Spacer(Modifier.width(6.dp))
        Text(note.text.ifEmpty { stringResource(R.string.note_mark) }, style = MaterialTheme.typography.bodySmall, color = colors.onNote, maxLines = if (open) 10 else 1, overflow = TextOverflow.Ellipsis)
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

/**
 * Improve transcription: the lecture's length and what wrote it now, the models with an estimate each (the Settings
 * default picked), the speaker pass first when it hasn't run, and what is kept. With no model downloaded for the
 * language, the one to get.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImproveSheet(state: ReviewState, actions: ReviewActions, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(state.improveDefault ?: state.refineModels.firstOrNull()?.id) }
    // Improving drops the word times the speaker pass reads: offer it first when it can run and hasn't.
    val offerSpeakersFirst = state.speakerPass == PassState.NotRun
    var speakersFirst by remember { mutableStateOf(offerSpeakersFirst) }
    val lecture = state.lecture
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.review_improve_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.review_improve_about, (lecture.durationMillis / 60_000).toInt(), languageName(lecture.language), LocalModelNames.current(lecture.refinedWith ?: lecture.segments.firstOrNull()?.modelId.orEmpty())),
                    style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
                )
            }
            if (state.refineModels.isEmpty()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.review_no_model, languageName(lecture.language)), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) }
                        state.improveToGet?.let { m ->
                            Button(onClick = { actions.getModel(m.id); onDismiss() }) { Text(stringResource(R.string.review_get_model, isolated(m.name), modelsSize(m.sizeBytes))) }
                        }
                    }
                }
                return@Column
            }
            Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                state.refineModels.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = picked == c.id, role = Role.RadioButton) { picked = c.id }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == c.id, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(isolated(c.name), style = MaterialTheme.typography.bodyLarge)
                            val facts = listOfNotNull(
                                stringResource(R.string.review_default).takeIf { c.id == state.improveDefault },
                                c.minutes?.let { stringResource(R.string.review_minutes, it) },
                                stringResource(if (c.npu) R.string.review_npu else R.string.review_cpu),
                            )
                            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (offerSpeakersFirst) {
                Row(
                    Modifier.fillMaxWidth().toggleable(speakersFirst, role = Role.Checkbox) { speakersFirst = it }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = speakersFirst, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.review_speakers_first), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.review_speakers_first_note), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
                    }
                }
            }
            Text(
                stringResource(R.string.review_kept), style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_cancel)) }
                Button(onClick = { picked?.let { actions.improve(it, speakersFirst && offerSpeakersFirst) }; onDismiss() }, enabled = picked != null) {
                    Text(stringResource(R.string.review_improve_button))
                }
            }
        }
    }
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
                    stringResource(if (lecture.refinedWith != null) R.string.review_version_refined else R.string.review_version_live, LocalModelNames.current(nowModel)),
                    stringResource(R.string.review_version_lines, lecture.segments.size, lecture.segments.count { it.edited }),
                    current = true, onClick = null,
                )
                lecture.versions.sortedByDescending { it.keptAtMillis }.forEach { v ->
                    VersionRow(
                        format.format(java.util.Date(v.keptAtMillis)),
                        stringResource(if (v.refined) R.string.review_version_refined else R.string.review_version_live, LocalModelNames.current(v.modelId)),
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

/**
 * "Tune on this lecture"'s result: fewer or more speakers than the setting now (or the same), how many of the lecture's
 * lines would match the user's labels then and now, and Apply (the voice-print model's setting, from the next lecture).
 */
@Composable
private fun TuneDialog(p: LectureReview.TuneProposal, onDismiss: () -> Unit, onApply: () -> Unit) {
    val r = p.result
    val direction = when {
        r.more < p.now -> stringResource(R.string.review_tune_fewer)
        r.more > p.now -> stringResource(R.string.review_tune_more)
        else -> stringResource(R.string.review_tune_same)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_tune_title)) },
        text = {
            Text(stringResource(R.string.review_tune_result, direction, r.matches, r.lines, r.matchesNow, r.speakers, r.speakersNow))
        },
        confirmButton = { TextButton(onClick = onApply, enabled = r.more != p.now) { Text(stringResource(R.string.review_tune_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** "Power: 1.2 W avg, 20 mWh/min, +4 °C" (or "on the charger: not measured"), small, under the lecture's bar. */
@Composable
private fun PowerLine(p: com.chencohensolution.transcriptor.recording.PowerReport) {
    val mw = p.averageMilliwatts
    val text = if (p.charging || mw == null) stringResource(R.string.review_power_charging)
    else stringResource(
        R.string.review_power, mw / 1000, p.milliwattHoursPerMinute ?: 0.0,
        (if ((p.temperatureRise ?: 0.0) >= 0) "+" else "") + String.format(java.util.Locale.getDefault(), "%.1f", p.temperatureRise ?: 0.0),
    )
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
