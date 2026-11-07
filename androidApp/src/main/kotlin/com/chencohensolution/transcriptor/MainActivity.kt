package com.chencohensolution.transcriptor

import android.content.ClipData
import java.io.File
import androidx.core.content.FileProvider
import android.content.Intent
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
import com.chencohensolution.transcriptor.notebook.RecordingNow
import com.chencohensolution.transcriptor.notebook.labelNames
import com.chencohensolution.transcriptor.notebook.notebooksState
import com.chencohensolution.transcriptor.review.LectureReview
import com.chencohensolution.transcriptor.models.ModelChoices
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.review.ModelChoice
import com.chencohensolution.transcriptor.review.ModelToGet
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The app: Notebooks first (the lectures, and Record), the lecture while recording (opened by Record, or from the
 * banner while it records), a recorded lecture opened (after Stop, or from the list), and Settings (for now the old
 * start screen's settings, until Settings › Models is designed).
 */
class MainActivity : ComponentActivity() {
    /** Plugged in while the app is open: the lectures waiting for the charger are improved. */
    private val powerConnected = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) = improvePendingIfCharging()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(powerConnected, android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED), RECEIVER_NOT_EXPORTED)
        improvePendingIfCharging()
    }

    override fun onStop() {
        unregisterReceiver(powerConnected)
        super.onStop()
    }

    /** Lectures wait to be improved on their own until the phone charges with the app open (a foreground start). */
    private fun improvePendingIfCharging() {
        lifecycleScope.launch {
            val waiting = withContext(Dispatchers.IO) { Recording.settings.improvePending.isNotEmpty() }
            val charging = getSystemService(android.os.BatteryManager::class.java).isCharging
            if (waiting && charging && !Recording.session.isRecording && Recording.refining.value == null) RecordingService.improvePending(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TranscriptorTheme {
              // Model names in the lecture's screen include those added from Hugging Face.
              androidx.compose.runtime.CompositionLocalProvider(com.chencohensolution.transcriptor.ui.LocalModelNames provides Recording.modelLibrary::nameOf) {
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
                        openLecture(lecture.id)
                    }
                }
                // The lists again when a lecture stops, one closes, or a pass on one ends; and finished lectures move
                // to the lectures folder.
                LaunchedEffect(live == null, opened == null, working.keys) {
                    withContext(Dispatchers.IO) {
                        Recording.migrateLibrary()
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
                        // Settings › Advanced; read when the screen opens (Settings is another screen).
                        val meterStyle = remember { Recording.settings.processorMeters }
                        val meters = rememberProcessorMeters(meterStyle)
                        LectureScreen(
                            state.copy(meterStyle = meterStyle, meters = meters.now, meterHistory = meters.history),
                            LiveLectureActions(
                                back = { showLive = false },
                                pauseOrResume = Recording.session::pauseOrResume,
                                stop = { openAfterStop = true; RecordingService.stop(this) },
                                addNote = Recording.session::addNote,
                                renameSpeaker = Recording.session::renameSpeaker,
                                editLine = Recording.session::editLine,
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
                                tuneSpeakers = r::tuneSpeakers, applySpeakerTuning = r::applySpeakerTuning,
                                improve = { model, speakersFirst -> RecordingService.refine(this, review.lecture, model, speakersFirst) },
                                getModel = { id ->
                                    lifecycleScope.launch(Dispatchers.IO) { Recording.modelLibrary.all.firstOrNull { it.id == id }?.let { Recording.models.download(it) } }
                                },
                                switchVersion = r::switchTo,
                                addNote = r::addNote, editNote = r::editNote, deleteNote = r::deleteNote,
                                shareWord = { lifecycleScope.launch(Dispatchers.IO) { r.word()?.let { (name, bytes) -> shareWord(name, bytes) } } },
                                saveWord = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val name = runCatching { r.saveWord() }.getOrNull() ?: return@launch
                                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, getString(R.string.review_word_saved, name), Toast.LENGTH_LONG).show() }
                                    }
                                },
                                deleteLecture = {
                                    val id = review.lecture.id
                                    r.close()
                                    lifecycleScope.launch(Dispatchers.IO) { Recording.delete(listOf(id)) }
                                },
                            ),
                        )
                    }
                    settings -> {
                        BackHandler { settings = false }
                        Settings(onBack = { settings = false }, chooseFolder = chooseFolder)
                    }
                    else -> Notebooks(
                        recordingNow = state?.let { RecordingNow(it.lecture.labels, it.lecture.title, it.elapsedMillis, it.lecture.id) },
                        working = working,
                        onRecord = { labels, language -> startLecture(labels, language); showLive = true },
                        onBackToRecording = { showLive = true },
                        onSettings = { settings = true },
                        chooseFolder = chooseFolder,
                    )
                }
              }
            }
        }
    }

    @Composable
    private fun Notebooks(
        recordingNow: RecordingNow?, working: Map<String, Float>,
        onRecord: (labels: List<String>, language: String) -> Unit, onBackToRecording: () -> Unit, onSettings: () -> Unit,
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
        var recordIn by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val (labels, language) = recordIn ?: return@rememberLauncherForActivityResult
            if (granted[Manifest.permission.RECORD_AUDIO] == true) onRecord(labels, language)
        }
        val io = { work: () -> Unit -> lifecycleScope.launch(Dispatchers.IO) { work() }; Unit }
        NotebooksScreen(
            notebooksState(library, now, Recording.utcOffset, recordingNow, working),
            NotebooksActions(
                openLecture = ::openLecture,
                record = { labels, language ->
                    recordIn = labels to language
                    permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                },
                backToRecording = onBackToRecording,
                // Made here, not in the background: the sheet that asked puts the new label on its moments at once.
                newLabel = { name -> runBlocking(Dispatchers.IO) { Recording.library.newLabel(name).id } },
                setLabels = { ids, add, remove -> io { Recording.setLabels(ids, add, remove) } },
                renameLabel = { id, name -> io { Recording.library.renameLabel(id, name) } },
                deleteLabel = { id -> io { Recording.deleteLabel(id) } },
                settings = onSettings,
                chooseFolder = chooseFolder,
                keepInApp = { asked = true; io { Recording.settings.lectureFolderAsked = true } },
                delete = { ids -> io { Recording.delete(ids) } },
            ),
            notice,
        )
    }

    /** Android's share sheet with a lecture's Word file, written to the app's cache for the FileProvider. */
    private fun shareWord(name: String, bytes: ByteArray) {
        val dir = File(cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, name).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, name)
            // Also as clip data, so the share sheet itself may read it for its preview.
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, name))
    }

    /** Opens a recorded moment, under its labels' names. */
    private fun openLecture(lectureId: String) {
        val library = Recording.library.library.value
        val labels = library.moments.firstOrNull { it.id == lectureId }?.labels.orEmpty()
        Recording.review.open(lectureId, labelNames(labels, library.labels))
    }

    /** Records a moment titled with today's date, with these [labels] (maybe none), in [language]. */
    private fun startLecture(labels: List<String>, language: String) {
        val title = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        RecordingService.start(this, labels, labelNames(labels, Recording.library.library.value.labels), language, title)
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

/**
 * Feeds the open lecture's passes from the recording service: running, done, or (the speaker pass) unavailable without
 * its models; the models to improve with, their estimates and the Settings default, or the model to get.
 */
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
        val models = withContext(Dispatchers.IO) { Recording.refineModels(lecture.language) }
        val default = withContext(Dispatchers.IO) { Recording.improveModelFor(lecture.language) }
        val toGet = if (models.isEmpty()) withContext(Dispatchers.IO) { Recording.improveSuggestion(lecture.language) } else null
        Recording.review.passes.value = LectureReview.Passes(
            speakers = when {
                !Recording.canFindSpeakers -> null
                speakers != null -> PassState.Running(speakers)
                lecture.speakerPass -> PassState.Done
                else -> PassState.NotRun
            },
            refine = when {
                refine != null -> PassState.Running(refine)
                lecture.refinedWith != null -> PassState.Done
                else -> PassState.NotRun
            },
            refineModels = models.map {
                ModelChoice(
                    it.id, it.name, ModelChoices.estimateMinutes(Recording.settings.improveSpeed(it.id), lecture.durationMillis),
                    npu = it.kind == ModelKind.WhisperNpu,
                )
            },
            improveDefault = default?.id,
            improveToGet = toGet?.let { ModelToGet(it.id, it.name, it.sizeBytes) },
        )
    }
}
