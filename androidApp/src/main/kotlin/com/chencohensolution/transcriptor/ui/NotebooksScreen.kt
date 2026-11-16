package com.chencohensolution.transcriptor.ui

import androidx.activity.compose.BackHandler
import com.chencohensolution.transcriptor.settings.RecordMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import com.chencohensolution.transcriptor.notebook.MIN_QUERY
import com.chencohensolution.transcriptor.notebook.SearchHit
import kotlinx.coroutines.delay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.models.Languages
import com.chencohensolution.transcriptor.notebook.Label
import com.chencohensolution.transcriptor.notebook.isCall
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.NotebooksState
import com.chencohensolution.transcriptor.notebook.RecordingNow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotebooksActions(
    /** Opens a recorded moment. */
    val openLecture: (lectureId: String) -> Unit = {},
    /** The list neared its end: read the next older page of the server's recordings. */
    val olderFromServer: () -> Unit = {},
    /** The list neared its top after older pages let the newest go: read the page just newer. */
    val newerFromServer: () -> Unit = {},
    /** Opens a moment a search found, at the line or note it found ([atMillis]; null: from where it was). */
    val openLectureAt: (lectureId: String, atMillis: Long?) -> Unit = { _, _ -> },
    /** The moments a query is found in (titles, people, labels, notes, what was said); reads files off the main thread. */
    val search: suspend (query: String) -> List<SearchHit> = { emptyList() },
    /** Starts a moment with these labels (maybe none) in [language], transcribed as it records, after, or not ([mode]). */
    val record: (labels: List<String>, language: String, mode: RecordMode) -> Unit = { _, _, _ -> },
    /** Keeps the Record bar's Live or After for next time. */
    val setRecordMode: (mode: RecordMode) -> Unit = {},
    val backToRecording: () -> Unit = {},
    /** Makes a label; returns its id. */
    val newLabel: (name: String) -> String = { "" },
    /** Adds [add] and removes [remove] on every moment in [lectureIds]. */
    val setLabels: (lectureIds: List<String>, add: Set<String>, remove: Set<String>) -> Unit = { _, _, _ -> },
    val renameLabel: (labelId: String, name: String) -> Unit = { _, _ -> },
    /** Deletes a label: its moments stay, without it. */
    val deleteLabel: (labelId: String) -> Unit = {},
    /** Puts a label's moments behind the phone's lock ("Needs fingerprint"), or takes them out. */
    val setLabelLocked: (labelId: String, locked: Boolean) -> Unit = { _, _ -> },
    val settings: () -> Unit = {},
    /** Opens the system's folder picker for the lectures folder. */
    val chooseFolder: () -> Unit = {},
    /** "Not now" on the question where lectures are kept. */
    val keepInApp: () -> Unit = {},
    /** Deletes moments for good (their folders: recording, transcript, notes), once their Undo is gone. */
    val delete: (lectureIds: List<String>) -> Unit = {},
    /** Tucks moments away, or brings them back. */
    val setTucked: (lectureIds: List<String>, tucked: Boolean) -> Unit = { _, _ -> },
    /** Asks for the phone's lock, then calls back to show the tucked moments (at once on a phone with no lock). */
    val revealTucked: (onRevealed: () -> Unit) -> Unit = { it() },
    /** Marks moments private: kept on the server, then deleted from the phone. Files and network: off the main thread. */
    val makePrivate: suspend (lectureIds: List<String>) -> PrivateOutcome = { PrivateOutcome(0) },
)

/** How marking moments private went: [done] of them left the phone; [notPaired] when there is no server; [failed] says why the rest stayed. */
data class PrivateOutcome(val done: Int, val notPaired: Boolean = false, val failed: String? = null)

/** What the Notebooks screen says about the lectures folder, on top of the list. */
sealed interface FolderNotice {
    /** None chosen yet: where should lectures be kept? */
    data object Ask : FolderNotice
    data class Moving(val name: String, val progress: Float) : FolderNotice
    /** The folder can't be reached any more. */
    data class Lost(val name: String) : FolderNotice
}

/**
 * The app's first screen (round 3's a): every moment newest first, grouped today / this week / earlier, each with all
 * its labels. The chips on top narrow the list to a label (or to
 * moments without one); moments without labels wait on top with the label their time suggests, one tap to add it. The Record bar has the labels the next moment gets (the
 * guessed ones, ✦, already on) and its language. Holding moments selects them; the label button sets their labels in
 * a sheet with a checkbox per label (some selected moments have it: a dash), where labels are also made, renamed and
 * deleted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
/**
 * What the list keeps while a moment or Settings is open, held by the screen that shows it: where it was scrolled,
 * and whether the phone's lock opened it. Not saved: a restart starts at the top, locked.
 */
class NotebooksPlace {
    val list = androidx.compose.foundation.lazy.LazyListState()
    var revealed by mutableStateOf(false)
}

@Composable
fun NotebooksScreen(
    all: NotebooksState, actions: NotebooksActions, folder: FolderNotice? = null, paired: Boolean = false,
    place: NotebooksPlace = remember { NotebooksPlace() },
    /** A moment is being opened (one only on the server takes a moment): a spinner over the list, which waits. */
    opening: Boolean = false,
) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        NotebooksContent(all, actions, folder, paired, place)
        if (opening) OpeningMoment()
    }
}

/** The list dimmed under a spinner, taking the taps, while a moment opens. */
@Composable
private fun OpeningMoment() {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.CircularProgressIndicator() }
}

@Composable
private fun NotebooksContent(all: NotebooksState, actions: NotebooksActions, folder: FolderNotice?, paired: Boolean, place: NotebooksPlace) {
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    // Tucked moments are shown after the phone's lock, until the app is opened again (not saved: a restart hides them).
    var revealed by place::revealed
    // Tucked moments, locked labels' and locked calls stay behind the phone's lock until it opens them.
    val state = all.copy(moments = all.moments.filter { it.id !in hidden && (revealed || !(it.tucked || it.locked)) })
    val tuckedOut = if (revealed) emptyList() else all.moments.filter { (it.tucked || it.locked) && it.id !in hidden }
    var askingPrivate by remember { mutableStateOf<List<String>?>(null) }
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    var privateBusy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedOne = stringResource(R.string.notebooks_deleted_one)
    val deletedMany = stringResource(R.string.notebooks_deleted_many)
    val undo = stringResource(R.string.notebooks_undo)
    fun deleteWithUndo(ids: List<String>) {
        hidden = hidden + ids
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(if (ids.size == 1) deletedOne else deletedMany.format(ids.size), actionLabel = undo, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) hidden = hidden - ids.toSet() else actions.delete(ids)
        }
    }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    fun toggle(id: String) {
        if (id in state.working) return
        selected = if (id in selected) selected - id else selected + id
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    // The list's filter: a label's id, NO_LABEL, or null for every moment.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    // The next moment's labels once the user changed them; null follows the filter and the guess.
    var chosen by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var language by rememberSaveable(state.language) { mutableStateOf(state.language) }
    var labeling by remember { mutableStateOf<List<String>?>(null) }
    var choosingLabels by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    var recordMode by rememberSaveable { mutableStateOf(state.recordMode) }
    // Search: the query while the search bar is open (null: closed), and what it found (null: still looking).
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<SearchHit>?>(emptyList()) }
    LaunchedEffect(query) {
        val q = query?.trim().orEmpty()
        if (q.length < MIN_QUERY) { hits = emptyList(); return@LaunchedEffect }
        hits = null
        // Not on every letter of a word being typed.
        delay(250)
        hits = actions.search(q)
    }
    BackHandler(enabled = query != null) { query = null }
    var creating by remember { mutableStateOf(false) }

    val guessed = state.guess?.labels.orEmpty().filter { id -> state.labels.any { it.id == id } }
    val nextLabels = chosen ?: (filter?.takeIf { it != NO_LABEL && it != CALLS_FILTER }?.let { listOf(it) } ?: guessed)
    val labelsById = state.labels.associateBy { it.id }
    val unlabeled = state.moments.filter { it.labels.isEmpty() }
    fun groupOf(m: LectureSummary): Int {
        val days = state.nowMillis / DAY - m.startedAtMillis / DAY
        return when {
            days == 0L -> R.string.notebooks_today
            days < 7 -> R.string.notebooks_this_week
            else -> R.string.notebooks_earlier
        }
    }
    fun inFilter(m: LectureSummary) = when (filter) {
        null -> true
        NO_LABEL -> m.labels.isEmpty()
        CALLS_FILTER -> isCall(m.source)
        else -> filter in m.labels
    }
    val shown = state.moments.filter(::inFilter).groupBy(::groupOf)
    // How many tucked moments each group leaves out: a pill under its title shows them after the phone's lock.
    val tuckedByGroup = tuckedOut.filter(::inFilter).groupingBy(::groupOf).eachCount()
    val groups = (shown.keys + tuckedByGroup.keys).sortedBy { listOf(R.string.notebooks_today, R.string.notebooks_this_week, R.string.notebooks_earlier).indexOf(it) }

    askingPrivate?.let { ids ->
        PrivateDialog(
            count = ids.size, paired = paired, busy = privateBusy,
            onDismiss = { if (!privateBusy) askingPrivate = null },
            onSettings = { askingPrivate = null; actions.settings() },
            onConfirm = {
                privateBusy = true
                scope.launch {
                    val outcome = actions.makePrivate(ids)
                    privateBusy = false
                    askingPrivate = null
                    selected = emptySet()
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(privateMessage(resources, outcome, ids.size), duration = SnackbarDuration.Long)
                }
            },
        )
    }

    labeling?.let { ids ->
        LabelsSheet(
            labels = state.labels, moments = state.moments.filter { it.id in ids },
            onDismiss = { labeling = null; selected = emptySet() },
            onSet = { add, remove -> actions.setLabels(ids, add, remove) },
            onNew = { name -> actions.newLabel(name).also { actions.setLabels(ids, setOf(it), emptySet()) } },
            onRename = actions.renameLabel, onDelete = actions.deleteLabel, onLock = actions.setLabelLocked,
        )
    }
    if (creating) NewLabelDialog(onDismiss = { creating = false }) { actions.newLabel(it); creating = false }
    if (choosingLanguage) {
        LanguageDialog(language, notebookLanguages(state.moments.map { it.language }), onDismiss = { choosingLanguage = false }) { language = it; choosingLanguage = false }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val searching = query
            if (searching != null) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { query = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.notebooks_search_close)) } },
                    title = {
                        TextField(
                            value = searching, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.notebooks_search_hint)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    },
                    actions = {
                        if (searching.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.notebooks_search_clear)) }
                    },
                )
            } else if (selected.isEmpty()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        if (state.moments.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Search, stringResource(R.string.notebooks_search)) }
                        IconButton(onClick = actions.settings) { Icon(Icons.Filled.Settings, stringResource(R.string.notebooks_settings)) }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.notebooks_clear_selection)) } },
                    title = { Text(stringResource(R.string.notebooks_selected, selected.size)) },
                    actions = {
                        IconButton(onClick = { labeling = selected.toList() }) { Icon(Icons.AutoMirrored.Outlined.Label, stringResource(R.string.notebooks_labels)) }
                        IconButton(onClick = { deleteWithUndo(selected.toList()); selected = emptySet() }) { Icon(Icons.Filled.Delete, stringResource(R.string.notebooks_delete)) }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.notebooks_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val allTucked = state.moments.filter { it.id in selected }.all { it.tucked }
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (allTucked) R.string.notebooks_bring_back else R.string.notebooks_tuck_away)) },
                                    leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) },
                                    onClick = { menu = false; actions.setTucked(selected.toList(), !allTucked); selected = emptySet() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notebooks_mark_private)) },
                                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                                    onClick = { menu = false; askingPrivate = selected.toList() },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    val r = state.recording
                    if (r != null) {
                        RecordingBanner(r, r.labels.mapNotNull { labelsById[it] }, actions.backToRecording)
                    } else {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    LazyRow(verticalAlignment = Alignment.CenterVertically) {
                                        if (nextLabels.isEmpty()) {
                                            item {
                                                AssistChip(
                                                    onClick = { choosingLabels = true },
                                                    label = { Text(stringResource(R.string.notebooks_no_labels)) },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(16.dp)) },
                                                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                                                )
                                            }
                                        }
                                        items(nextLabels.mapNotNull { labelsById[it] }, key = { it.id }) { l ->
                                            AssistChip(
                                                onClick = { choosingLabels = true },
                                                label = { Text(l.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                leadingIcon = { LabelDot(l.color) },
                                                trailingIcon = if (chosen == null && l.id in guessed) ({ Icon(Icons.Filled.AutoAwesome, stringResource(R.string.notebooks_suggested), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }) else null,
                                                modifier = Modifier.padding(end = 6.dp),
                                            )
                                        }
                                        if (nextLabels.isNotEmpty()) {
                                            item {
                                                IconButton(onClick = { choosingLabels = true }) { Icon(Icons.Filled.Add, stringResource(R.string.notebooks_labels)) }
                                            }
                                        }
                                    }
                                    LabelsMenu(
                                        expanded = choosingLabels, labels = state.labels, checked = nextLabels.toSet(), guessed = guessed.toSet(),
                                        onDismiss = { choosingLabels = false },
                                        onNone = { chosen = emptyList(); choosingLabels = false },
                                        onToggle = { id -> chosen = if (id in nextLabels) nextLabels - id else nextLabels + id },
                                        onNew = { choosingLabels = false; creating = true },
                                    )
                                }
                                AssistChip(
                                    onClick = { choosingLanguage = true },
                                    label = { Text(languageName(language), maxLines = 1) },
                                    leadingIcon = { Icon(Icons.Filled.Language, null, Modifier.size(16.dp)) },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            // Live, After or Only record: transcribed while it records, after it stops (easier on the
                            // battery), or not until asked. The guessed labels keep their ✦ on the chips above.
                            Row(Modifier.padding(top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        when (recordMode) {
                                            RecordMode.Live -> R.string.notebooks_record_live_note
                                            RecordMode.After -> R.string.notebooks_record_after_note
                                            RecordMode.Only -> R.string.notebooks_record_only_note
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
                                )
                                listOf(
                                    RecordMode.Live to R.string.notebooks_record_live, RecordMode.After to R.string.notebooks_record_after,
                                    RecordMode.Only to R.string.notebooks_record_only,
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = recordMode == mode, onClick = { recordMode = mode; actions.setRecordMode(mode) },
                                        label = { Text(stringResource(label)) }, modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            Button(
                                onClick = { actions.record(nextLabels, language, recordMode); chosen = null }, shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Icon(Icons.Filled.Mic, null)
                                Text(stringResource(R.string.notebooks_record), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val searching = query
        if (searching != null) {
            SearchResults(searching, hits, labelsById, state, padding) { hit -> actions.openLectureAt(hit.moment.id, hit.atMillis) }
            return@Scaffold
        }
        // The server's recordings come a page at a time: near the end the next older page, near the top (after older
        // pages let the newest go) the page just newer. The list keeps its place by each row's key.
        val list = place.list
        val nearEnd by remember { androidx.compose.runtime.derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.index >= list.layoutInfo.totalItemsCount - 15 } == true } }
        val nearTop by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex <= 10 } }
        // Asked again each time a page arrives: a page whose recordings are all moments here already (or all out of
        // the label shown) adds no row, and the list still needs the next one.
        // Pages that showed nothing (all behind the lock, or under another label) are read on only a few times in a
        // row: then a button asks before going further back, so the list doesn't walk through years on its own.
        var emptyPages by remember { mutableStateOf(-1) } // the first count is the list as it opens, not a page
        var rowsBefore by remember { mutableStateOf(state.moments.size) }
        LaunchedEffect(state.serverLoading) {
            if (state.serverLoading) return@LaunchedEffect
            emptyPages = if (state.moments.size == rowsBefore) emptyPages + 1 else 0
            rowsBefore = state.moments.size
        }
        val readOn = emptyPages < EMPTY_PAGES
        LaunchedEffect(nearEnd, state.serverOlder, state.serverLoading, state.moments.size, readOn) {
            if (nearEnd && state.serverOlder && !state.serverLoading && readOn) actions.olderFromServer()
        }
        LaunchedEffect(nearTop, state.serverNewer, state.serverLoading, state.moments.size) { if (nearTop && state.serverNewer && !state.serverLoading) actions.newerFromServer() }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            folder?.let { notice -> item(key = "folder") { FolderCard(notice, actions) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    item {
                        FilterChip(selected = filter == null, onClick = { filter = null; chosen = null }, label = { Text(stringResource(R.string.notebooks_all)) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    if (unlabeled.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = filter == NO_LABEL, onClick = { filter = if (filter == NO_LABEL) null else NO_LABEL; chosen = null },
                                label = { Text(stringResource(R.string.notebooks_no_label_chip, unlabeled.size)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    // Calls, by their type (not a label): when there are any.
                    if (all.moments.any { isCall(it.source) }) item {
                        FilterChip(
                            selected = filter == CALLS_FILTER, onClick = { filter = if (filter == CALLS_FILTER) null else CALLS_FILTER; chosen = null },
                            label = { Text(stringResource(R.string.label_calls)) }, leadingIcon = { Icon(Icons.Filled.Call, null, Modifier.size(16.dp)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    items(state.labels, key = { it.id }) { l ->
                        FilterChip(
                            selected = filter == l.id, onClick = { filter = if (filter == l.id) null else l.id; chosen = null },
                            label = { Text(l.name, maxLines = 1) }, leadingIcon = { LabelDot(l.color) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    item {
                        OutlinedButton(onClick = { creating = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Filled.Add, stringResource(R.string.notebooks_new_label))
                        }
                    }
                }
            }
            if (state.moments.isEmpty()) item { Empty() }
            // One list by date: a moment without labels is a row like the others, with a quiet way to give it one
            // (the label its time suggests, else the labels sheet).
            groups.forEach { group ->
                val rows = shown[group].orEmpty()
                item(key = group) { GroupTitle(stringResource(group)) }
                tuckedByGroup[group]?.let { count ->
                    item(key = "tucked-$group") { TuckedPill(count) { actions.revealTucked { revealed = true } } }
                }
                items(rows, key = { it.id }) { m ->
                    val suggestion = state.suggested[m.id]?.takeIf { m.labels.isEmpty() && m.id !in state.working }?.let { labelsById[it] }
                    MomentRow(
                        m, m.labels.filter { it != filter }.mapNotNull { labelsById[it] }, state, selected = m.id in selected,
                        onOpen = { if (selected.isEmpty()) actions.openLecture(m.id) else toggle(m.id) },
                        onLongPress = { toggle(m.id) },
                    ) {
                        if (m.labels.isEmpty() && selected.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            if (suggestion != null) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { actions.setLabels(listOf(m.id), setOf(suggestion.id), emptySet()) }.padding(vertical = 6.dp).padding(end = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LabelDot(suggestion.color)
                                    Text(
                                        stringResource(R.string.notebooks_add, isolated(suggestion.name)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            Text(
                                stringResource(if (suggestion != null) R.string.notebooks_labels_more else R.string.notebooks_add_label_quiet),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { labeling = listOf(m.id) }.padding(vertical = 6.dp).padding(end = 12.dp),
                            )
                        }
                    }
                }
            }
            if (state.serverOlder || state.serverLoading) item(key = "server-more") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (state.serverLoading || readOn) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { emptyPages = 0; rowsBefore = state.moments.size }) { Text(stringResource(R.string.notebooks_older)) }
                }
            }
        }
    }
}

private const val NO_LABEL = "-"

/** The filter for calls, by their type. */
private const val CALLS_FILTER = "~calls"

/** Pages in a row that added no row before the list stops reading on by itself. */
private const val EMPTY_PAGES = 3
private const val DAY = 24 * 60 * 60_000L

@Composable
private fun LabelDot(color: Int) = Box(Modifier.size(8.dp).background(notebookColor(color), CircleShape))

/** A moment's labels, in their colors: "● Psychology  ● Exam". */

@Composable
private fun LabelPills(labels: List<Label>) {
    if (labels.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelDot(l.color)
                Text(l.name, style = MaterialTheme.typography.labelMedium, color = notebookColor(l.color), maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** "2 tucked away · Show": the tucked moments a group leaves out, shown after the phone's lock. */
@Composable
private fun TuckedPill(count: Int, onShow: () -> Unit) {
    AssistChip(
        onClick = onShow,
        label = { Text(pluralStringResource(R.plurals.notebooks_tucked_pill, count, count)) },
        leadingIcon = { Icon(Icons.Filled.VisibilityOff, null, Modifier.size(16.dp)) },
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

/**
 * Asks before marking moments private: they are kept on the server and deleted from the phone, and Audio Logs won't
 * show them again. Without a server there is nowhere to keep them: it says so and offers Settings.
 */
@Composable
private fun PrivateDialog(count: Int, paired: Boolean, busy: Boolean, onDismiss: () -> Unit, onSettings: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text(pluralStringResource(R.plurals.notebooks_private_title, count, count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (paired) R.string.notebooks_private_body else R.string.notebooks_private_no_server))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (paired) TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.notebooks_private_confirm)) }
            else TextButton(onClick = onSettings) { Text(stringResource(R.string.notebooks_private_settings)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.notebooks_cancel)) } },
    )
}

private fun privateMessage(res: android.content.res.Resources, outcome: PrivateOutcome, asked: Int): String = when {
    outcome.notPaired -> res.getString(R.string.notebooks_private_no_server)
    outcome.done == asked -> res.getQuantityString(R.plurals.notebooks_private_done, asked, asked)
    else -> res.getString(R.string.notebooks_private_failed, outcome.done, asked, outcome.failed.orEmpty())
}

/** A small mark on a moment's row: an icon and its word. */
@Composable
private fun MomentMark(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentRow(
    m: LectureSummary, labels: List<Label>, state: NotebooksState, selected: Boolean,
    onOpen: () -> Unit, onLongPress: () -> Unit, extra: @Composable () -> Unit = {},
) {
    val l = m
    Column(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LabelPills(labels)
        // Marks: tucked away, its audio on the server only (opening brings it), and a call or voice message.
        val source = when (m.source?.lowercase()) {
            "call" -> stringResource(R.string.notebooks_source_call)
            "folder:whatsapp", "whatsapp" -> stringResource(R.string.notebooks_source_voice_message)
            else -> null
        }
        if (m.tucked || m.locked || m.notOnServer || source != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (m.tucked) MomentMark(Icons.Filled.VisibilityOff, stringResource(R.string.notebooks_tucked))
            if (m.locked) MomentMark(Icons.Filled.Lock, stringResource(R.string.notebooks_locked))
            if (source != null) MomentMark(if (m.source.equals("call", true)) Icons.Filled.Call else Icons.Filled.Mic, source)
            // Only on the phone while a server is paired (not backed up yet); nothing for what the server holds.
            if (m.notOnServer) MomentMark(Icons.Filled.CloudOff, stringResource(R.string.notebooks_not_on_server))
        }
        Text(l.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = titleDirection(m.language)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Who was in it, and how it started: what tells one dated moment from the next.
        peopleLine(m)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (m.preview.isNotEmpty()) Text(
            "“" + isolated(m.preview) + "”", maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall.copy(textDirection = textDirectionOf(m.language)), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            listOfNotNull(
                if (m.labels.isEmpty()) weekdayAndTime(l.startedAtMillis) else momentDay(l.startedAtMillis, state.nowMillis),
                momentLength(l.durationMillis),
                if (l.noteCount > 0) stringResource(R.string.notebooks_notes, l.noteCount) else null,
                when {
                    l.pendingTranscript -> stringResource(R.string.notebooks_not_transcribed)
                    l.refined -> stringResource(R.string.notebooks_refined)
                    else -> null
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
        )
        state.working[l.id]?.let { p ->
            Text(stringResource(R.string.notebooks_improving, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        extra()
    }
}

/** "Hen, Maya and 1 more": the named speakers, the one who talked most first, and how many have no name; null for none. */
@Composable
private fun peopleLine(m: LectureSummary): String? {
    val names = m.people.take(4).joinToString(", ") { isolated(it) }
    val others = m.unnamedPeople + (m.people.size - 4).coerceAtLeast(0)
    return when {
        names.isEmpty() && others < 2 -> null
        names.isEmpty() -> pluralStringResource(R.plurals.notebooks_voices, others, others)
        others == 0 -> names
        else -> stringResource(R.string.notebooks_people_more, names, others)
    }
}

/**
 * What a search found: a row per moment with its title, where the query is (who said it, or a note, a person, a
 * label), the text around it with the match in bold, and how many more places in that moment. Tapping opens the
 * moment at that line.
 */
@Composable
private fun SearchResults(
    query: String, hits: List<SearchHit>?, labelsById: Map<String, Label>, state: NotebooksState,
    padding: PaddingValues, onOpen: (SearchHit) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
        when {
            query.trim().length < MIN_QUERY -> item { SearchNote(stringResource(R.string.notebooks_search_start)) }
            hits == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) }
            hits.isEmpty() -> item { SearchNote(stringResource(R.string.notebooks_search_none, isolated(query.trim()))) }
            else -> items(hits, key = { it.moment.id }) { hit ->
                val m = hit.moment
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(hit) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    LabelPills(m.labels.mapNotNull { labelsById[it] })
                    Text(m.title, style = MaterialTheme.typography.bodyLarge.copy(textDirection = titleDirection(m.language)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val lead = when (hit.where) {
                        SearchHit.Where.Line -> hit.speaker.takeIf { it.isNotEmpty() }?.let { isolated(it) + ": " }.orEmpty()
                        SearchHit.Where.Note -> stringResource(R.string.notebooks_search_note) + " "
                        SearchHit.Where.Person -> stringResource(R.string.notebooks_search_person) + " "
                        SearchHit.Where.Label -> stringResource(R.string.notebooks_search_label) + " "
                        SearchHit.Where.Title -> ""
                    }
                    if (hit.where != SearchHit.Where.Title) Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(lead) }
                            append(hit.snippet.substring(0, hit.matchStart))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(hit.snippet.substring(hit.matchStart, hit.matchEnd)) }
                            append(hit.snippet.substring(hit.matchEnd))
                        },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            momentDay(m.startedAtMillis, state.nowMillis), momentLength(m.durationMillis),
                            hit.atMillis?.takeIf { hit.where == SearchHit.Where.Line || hit.where == SearchHit.Where.Note }?.let { stringResource(R.string.notebooks_search_at, formatClock(it)) },
                            hit.more.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.notebooks_search_more, it, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}

/** The labels for the next moment, as a menu of checkboxes: No labels, the guessed ones marked ✦, then New label. */
@Composable
private fun LabelsMenu(
    expanded: Boolean, labels: List<Label>, checked: Set<String>, guessed: Set<String>,
    onDismiss: () -> Unit, onToggle: (String) -> Unit, onNew: () -> Unit, onNone: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.notebooks_no_labels)) },
            leadingIcon = { androidx.compose.material3.RadioButton(selected = checked.isEmpty(), onClick = null) },
            onClick = onNone,
        )
        HorizontalDivider()
        labels.sortedByDescending { it.id in guessed }.forEach { l ->
            DropdownMenuItem(
                text = { Text(l.name) },
                leadingIcon = { TriStateCheckbox(state = if (l.id in checked) ToggleableState.On else ToggleableState.Off, onClick = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (l.id in guessed) Icon(Icons.Filled.AutoAwesome, stringResource(R.string.notebooks_suggested), Modifier.padding(end = 8.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        LabelDot(l.color)
                    }
                },
                onClick = { onToggle(l.id) },
            )
        }
        if (labels.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.notebooks_new_label)) }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = onNew)
    }
}

/**
 * The labels of [moments]: a checkbox per label, a dash when only some of them have it; a tap sets it on all of them.
 * New label makes one and puts it on them; the pencil renames a label or deletes it (its moments stay).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabelsSheet(
    labels: List<Label>, moments: List<LectureSummary>, onDismiss: () -> Unit,
    onSet: (add: Set<String>, remove: Set<String>) -> Unit, onNew: (String) -> Unit,
    onRename: (String, String) -> Unit, onDelete: (String) -> Unit,
    onLock: (String, Boolean) -> Unit = { _, _ -> },
) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Label?>(null) }
    if (creating) NewLabelDialog(onDismiss = { creating = false }) { onNew(it); creating = false }
    editing?.let { l ->
        EditLabelDialog(
            l, onDismiss = { editing = null }, onRename = { onRename(l.id, it); editing = null }, onDelete = { onDelete(l.id); editing = null },
            onLock = { onLock(l.id, it) },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                if (moments.size == 1) stringResource(R.string.notebooks_labels_of, isolated(moments.single().title)) else stringResource(R.string.notebooks_labels_of_many, moments.size),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(labels, key = { it.id }) { l ->
                    val count = moments.count { l.id in it.labels }
                    val toggle = when (count) {
                        0 -> ToggleableState.Off
                        moments.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { if (toggle == ToggleableState.On) onSet(emptySet(), setOf(l.id)) else onSet(setOf(l.id), emptySet()) }
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = toggle, onClick = null, modifier = Modifier.padding(12.dp))
                        LabelDot(l.color)
                        Text(l.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { editing = l }) { Icon(Icons.Filled.Edit, stringResource(R.string.notebooks_edit_label), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(R.string.notebooks_new_label), Modifier.padding(start = 16.dp))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 16.dp)) { Text(stringResource(R.string.notebooks_done)) }
        }
    }
}

@Composable
private fun NewLabelDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_new_label)) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.notebooks_new_name)) }, placeholder = { Text(stringResource(R.string.notebooks_new_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.notebooks_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

@Composable
private fun EditLabelDialog(label: Label, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit, onLock: (Boolean) -> Unit = {}) {
    var name by remember { mutableStateOf(label.name) }
    var locked by remember { mutableStateOf(label.locked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_edit_label)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.notebooks_new_name)) }, modifier = Modifier.fillMaxWidth())
                // Its moments behind the phone's lock, as calls can be: for private recordings, while lectures stay open.
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp)
                        .toggleable(value = locked, role = androidx.compose.ui.semantics.Role.Switch) { locked = it; onLock(it) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.notebooks_label_locked), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.notebooks_label_locked_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = locked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
                }
                TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.notebooks_delete_label), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.notebooks_delete_label_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.notebooks_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

@Composable
private fun LanguageDialog(language: String, languages: List<String>, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebooks_language_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(languages) { tag ->
                    Text(
                        languageName(tag), fontWeight = if (tag == language) FontWeight.SemiBold else null,
                        color = if (tag == language) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(tag) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

@Composable
private fun RecordingBanner(recording: RecordingNow, labels: List<Label>, onBack: () -> Unit) {
    val red = LocalTranscriptorColors.current.recording
    Surface(color = red.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onBack)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(red, CircleShape))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.notebooks_recording, formatClock(recording.elapsedMillis)), fontWeight = FontWeight.SemiBold, color = red)
                Text(
                    labels.joinToString(", ") { it.name }.ifEmpty { stringResource(R.string.notebooks_no_labels) },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = red, contentColor = Color.White)) { Text(stringResource(R.string.notebooks_back_to_lecture)) }
        }
    }
}

@Composable
private fun Empty() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.notebooks_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.notebooks_empty_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun weekdayAndTime(millis: Long): String = stringResource(
    R.string.notebooks_slot,
    SimpleDateFormat("EEEE", LocalConfiguration.current.locales[0]).format(Date(millis)),
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis)),
)

@Composable
private fun momentDay(millis: Long, now: Long): String = when (now / DAY - millis / DAY) {
    0L -> stringResource(R.string.notebooks_today)
    1L -> stringResource(R.string.notebooks_yesterday)
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}

@Composable
internal fun momentLength(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    return when {
        minutes < 60 -> stringResource(R.string.notebooks_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.notebooks_hours, minutes / 60)
        else -> stringResource(R.string.notebooks_hours_minutes, minutes / 60, minutes % 60)
    }
}

/**
 * The languages a moment can be in, most likely first: the phone's, those in [used], those with
 * models of their own (Hebrew's), then every other language Whisper knows.
 */
@Composable
internal fun notebookLanguages(used: List<String> = emptyList()): List<String> {
    val locales = LocalConfiguration.current.locales
    return Languages.ordered((0 until locales.size()).map { locales[it].language }, used)
}

/** A label's color, from the palette by its [index]. */
@Composable
internal fun notebookColor(index: Int): Color = LocalTranscriptorColors.current.speakers.let { it[index % it.size] }

/** A language's name in the phone's language ("עברית", "English"). */
@Composable
internal fun languageName(tag: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return Locale.forLanguageTag(tag).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/** The lectures folder: the question where to keep lectures, the move into the folder, or the folder lost. */
@Composable
private fun FolderCard(notice: FolderNotice, actions: NotebooksActions) {
    val lost = notice is FolderNotice.Lost
    Surface(
        color = if (lost) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (notice) {
                FolderNotice.Ask -> {
                    Text(stringResource(R.string.folder_ask_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.folder_ask_note), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.chooseFolder) { Text(stringResource(R.string.folder_choose)) }
                        TextButton(onClick = actions.keepInApp) { Text(stringResource(R.string.folder_not_now)) }
                    }
                }
                is FolderNotice.Moving -> {
                    Text(stringResource(R.string.folder_moving, notice.name, (notice.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { notice.progress }, modifier = Modifier.fillMaxWidth())
                }
                is FolderNotice.Lost -> {
                    Text(stringResource(R.string.folder_lost, notice.name), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions.chooseFolder) { Text(stringResource(R.string.folder_choose_again)) }
                }
            }
        }
    }
}
