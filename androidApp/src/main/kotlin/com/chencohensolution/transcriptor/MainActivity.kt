package com.chencohensolution.transcriptor

import android.Manifest
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.notebook.RecordingNow
import com.chencohensolution.transcriptor.notebook.UNFILED
import com.chencohensolution.transcriptor.notebook.notebooksState
import com.chencohensolution.transcriptor.review.LectureReview
import com.chencohensolution.transcriptor.review.ModelChoice
import com.chencohensolution.transcriptor.review.PassState
import com.chencohensolution.transcriptor.ui.LectureScreen
import com.chencohensolution.transcriptor.ui.LiveLectureActions
import com.chencohensolution.transcriptor.ui.FolderNotice
import com.chencohensolution.transcriptor.ui.NotebooksActions
import com.chencohensolution.transcriptor.ui.NotebooksScreen
import com.chencohensolution.transcriptor.ui.ReviewActions
import com.chencohensolution.transcriptor.ui.ReviewScreen
import com.chencohensolution.transcriptor.ui.TranscriptorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The app: Notebooks first (the lectures, and Record), the lecture while recording (opened by Record, or from the
 * banner while it records), a recorded lecture opened (after Stop, or from the list), and Settings (for now the old
 * start screen's settings, until Settings › Models is designed).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TranscriptorTheme {
                val live by Recording.session.state.collectAsStateWithLifecycle()
                val opened by Recording.review.state.collectAsStateWithLifecycle()
                val last by Recording.lastLecture.collectAsStateWithLifecycle()
                val working by Recording.working.collectAsStateWithLifecycle(emptyMap())
                var openAfterStop by remember { mutableStateOf(false) }
                // The recording lecture's screen is shown, not Notebooks (with its banner back to it).
                var showLive by rememberSaveable { mutableStateOf(true) }
                var settings by rememberSaveable { mutableStateOf(false) }
                // After Stop, the lecture just recorded opens once its last lines are in.
                LaunchedEffect(last, live) {
                    val lecture = last
                    if (openAfterStop && live == null && lecture != null) {
                        openAfterStop = false
                        openLecture(lecture.notebookId, lecture.id)
                    }
                }
                // The lists again when a lecture stops, one closes, or a pass on one ends; and finished lectures move
                // to the lectures folder.
                LaunchedEffect(live == null, opened == null, working.keys) {
                    withContext(Dispatchers.IO) {
                        Recording.library.reload()
                        runCatching { Recording.archive() }.onFailure { android.util.Log.e("MainActivity", "archiving failed", it) }
                    }
                }
                val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                    if (tree != null) lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { Recording.chooseFolder(tree) }.onFailure { e ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, getString(R.string.folder_failed, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                // The system's picker opens on Documents, where the app suggests its Transcriptor folder.
                val chooseFolder = { pickFolder.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")) }
                CompressPending()
                ReviewPasses()
                val state = live
                val review = opened
                when {
                    state != null && showLive -> {
                        BackHandler { showLive = false }
                        LectureScreen(
                            state,
                            LiveLectureActions(
                                back = { showLive = false },
                                pauseOrResume = Recording.session::pauseOrResume,
                                stop = { openAfterStop = true; RecordingService.stop(this) },
                                addNote = Recording.session::addNote,
                                renameSpeaker = Recording.session::renameSpeaker,
                            ),
                        )
                    }
                    review != null -> {
                        BackHandler { Recording.review.close() }
                        val r = Recording.review
                        ReviewScreen(
                            review,
                            ReviewActions(
                                back = r::close, playPause = r::playPause, seek = r::seek, playLine = r::playLine,
                                cycleSpeed = r::cycleSpeed, editLine = r::editLine, setLineSpeaker = r::setLineSpeaker,
                                renameSpeaker = { id, name -> lifecycleScope.launch(Dispatchers.IO) { r.renameSpeaker(id, name) } },
                                findSpeakers = { RecordingService.findSpeakers(this, review.lecture) },
                                refine = { model -> RecordingService.refine(this, review.lecture, model) },
                                switchVersion = r::switchTo,
                                addNote = r::addNote, editNote = r::editNote, deleteNote = r::deleteNote,
                            ),
                        )
                    }
                    settings -> {
                        BackHandler { settings = false }
                        SettingsScreen(onBack = { settings = false }, chooseFolder = chooseFolder)
                    }
                    else -> Notebooks(
                        recordingNow = state?.let { RecordingNow(it.lecture.notebookId.takeIf { id -> id != UNFILED }, it.lecture.title, it.elapsedMillis, it.lecture.id) },
                        working = working,
                        onRecord = { notebookId, language -> startLecture(notebookId, language); showLive = true },
                        onBackToRecording = { showLive = true },
                        onSettings = { settings = true },
                        chooseFolder = chooseFolder,
                    )
                }
            }
        }
    }

    @Composable
    private fun Notebooks(
        recordingNow: RecordingNow?, working: Map<String, Float>,
        onRecord: (notebookId: String?, language: String) -> Unit, onBackToRecording: () -> Unit, onSettings: () -> Unit,
        chooseFolder: () -> Unit,
    ) {
        val library by Recording.library.library.collectAsStateWithLifecycle()
        val folder by Recording.folder.collectAsStateWithLifecycle()
        var asked by rememberSaveable { mutableStateOf(Recording.settings.lectureFolderAsked) }
        val notice = when (val f = folder) {
            Recording.Folder.NotSet -> FolderNotice.Ask.takeIf { !asked }
            is Recording.Folder.Moving -> FolderNotice.Moving(f.name, f.progress)
            is Recording.Folder.Lost -> FolderNotice.Lost(f.name)
            is Recording.Folder.Ready -> null
        }
        // The clock, for "today" and the guess: once a minute.
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(60_000)
                value = System.currentTimeMillis()
            }
        }
        var recordIn by remember { mutableStateOf<Pair<String?, String>?>(null) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val (notebookId, language) = recordIn ?: return@rememberLauncherForActivityResult
            if (granted[Manifest.permission.RECORD_AUDIO] == true) onRecord(notebookId, language)
        }
        val io = { work: () -> Unit -> lifecycleScope.launch(Dispatchers.IO) { work() }; Unit }
        NotebooksScreen(
            notebooksState(library, now, Recording.utcOffset, recordingNow, working),
            NotebooksActions(
                openLecture = ::openLecture,
                record = { notebookId, language ->
                    recordIn = notebookId to language
                    permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                },
                backToRecording = onBackToRecording,
                newNotebook = { title, language -> io { Recording.library.create(title, language) } },
                assign = { lectureId, notebookId -> io { Recording.file(lectureId, notebookId) } },
                assignToNew = { lectureId, title, language -> io { Recording.file(lectureId, Recording.library.create(title, language).id) } },
                settings = onSettings,
                chooseFolder = chooseFolder,
                keepInApp = { asked = true; io { Recording.settings.lectureFolderAsked = true } },
            ),
            notice,
        )
    }

    /** Opens a recorded lecture, under its notebook's title ("Not in a notebook" for an unfiled one). */
    private fun openLecture(notebookId: String, lectureId: String) {
        val title = Recording.library.library.value.notebooks.firstOrNull { it.id == notebookId }?.title ?: getString(R.string.notebooks_unfiled_title)
        Recording.review.open(notebookId, lectureId, title)
    }

    /** Records a lecture titled with today's date, in a notebook, or in none ([UNFILED]) in [language]. */
    private fun startLecture(notebookId: String?, language: String) {
        val title = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        val notebook = Recording.library.library.value.notebooks.firstOrNull { it.id == notebookId }
            ?: Notebook(UNFILED, getString(R.string.notebooks_unfiled_title), language, emptyList())
        RecordingService.start(this, notebook, title)
    }
}

/** Lectures recorded as WAV and waiting to be compressed to FLAC (the app was closed first): compressed on launch. */
@Composable
private fun CompressPending() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val pending = withContext(Dispatchers.IO) { Recording.compression.pending() }
        if (pending.isNotEmpty() && Recording.compressing.value == null) RecordingService.compressPending(context)
    }
}

/** Feeds the open lecture's passes from the recording service: running, done, or unavailable without their models. */
@Composable
private fun ReviewPasses() {
    val finding by Recording.findingSpeakers.collectAsStateWithLifecycle()
    val refining by Recording.refining.collectAsStateWithLifecycle()
    val opened by Recording.review.state.collectAsStateWithLifecycle()
    val lecture = opened?.lecture
    LaunchedEffect(finding, refining, lecture?.speakerPass, lecture?.refinedWith, lecture?.id) {
        if (lecture == null) { Recording.review.passes.value = LectureReview.Passes(); return@LaunchedEffect }
        val speakers = finding?.takeIf { it.first == lecture.id }?.second
        val refine = refining?.takeIf { it.first == lecture.id }?.second
        val models = Recording.refineModels(lecture.language)
        Recording.review.passes.value = LectureReview.Passes(
            speakers = when {
                !Recording.canFindSpeakers -> null
                speakers != null -> PassState.Running(speakers)
                lecture.speakerPass -> PassState.Done
                else -> PassState.NotRun
            },
            refine = when {
                models.isEmpty() || !Recording.settings.afterLecture -> null
                refine != null -> PassState.Running(refine)
                lecture.refinedWith != null -> PassState.Done
                else -> PassState.NotRun
            },
            refineModels = models.map { ModelChoice(it.id, it.name) },
        )
    }
}
