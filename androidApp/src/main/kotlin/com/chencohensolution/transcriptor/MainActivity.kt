package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.settings.allows
import com.chencohensolution.transcriptor.settings.RecordMode
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
import androidx.compose.animation.togetherWith
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.chencohensolution.transcriptor.ui.PlaceActions
import com.chencohensolution.transcriptor.ui.LiveLectureActions
import com.chencohensolution.transcriptor.ui.FolderNotice
import com.chencohensolution.transcriptor.ui.NotebooksActions
import com.chencohensolution.transcriptor.ui.NotebooksScreen
import com.chencohensolution.transcriptor.ui.PrivateOutcome
import com.chencohensolution.transcriptor.backup.PrivateResult
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
            val waiting = withContext(Dispatchers.IO) {
                Recording.settings.improvePending.isNotEmpty() || (Recording.backup.callMoments && Recording.backup.callsWaitingForText().isNotEmpty())
            }
            val charging = getSystemService(android.os.BatteryManager::class.java).isCharging
            if (waiting && charging && !Recording.session.isRecording && Recording.refining.value == null) RecordingService.improvePending(this@MainActivity)
        }
    }

    /** Asks to show the moment recording now (a long press on the Quick Settings tile): counted, so each one shows it. */
    private val showRecording = androidx.compose.runtime.mutableIntStateOf(0)

    /** The moment being opened: one only on the server takes a moment to become one here, under a spinner. */
    private val opening = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_RECORDING, false)) showRecording.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SHOW_RECORDING, false)) showRecording.intValue++
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
                // The list's scroll and lock, kept while a moment or Settings is open, so Back returns to the same place.
                val notebooksPlace = remember { com.chencohensolution.transcriptor.ui.NotebooksPlace() }
                LaunchedEffect(showRecording.intValue) { if (showRecording.intValue > 0) { showLive = true; settings = false } }
                // A new install opens on "Get your models" (FirstRun), until it is answered.
                var firstRun by rememberSaveable { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) { if (firstRun == null) firstRun = withContext(Dispatchers.IO) { offerFirstRun() } }
                // After Stop, the lecture just recorded opens once its last lines are in.
                LaunchedEffect(last, live) {
                    val lecture = last
                    if (openAfterStop && live == null && lecture != null) {
                        openAfterStop = false
                        openLecture(lecture.id)
                    }
                }
                // The lists again when a lecture stops, one closes, or a pass on one ends; finished lectures are handed
                // to the server backup (when paired) and move to the lectures folder.
                LaunchedEffect(live == null, opened == null, working.keys) {
                    withContext(Dispatchers.IO) {
                        // The list from the index first (one small file), then the slower work in the lectures folder.
                        Recording.library.reload()
                        Recording.migrateLibrary()
                        Recording.checkIndexOnce()
                        Recording.backUp(this@MainActivity)
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
                // The system's picker opens on Documents, where the app suggests its Audio Logs folder.
                val chooseFolder = { pickFolder.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")) }
                CompressPending()
                ReviewPasses()
                val state = live
                val review = opened
                // The screens change with a short cross-fade; Record on the first screen morphs into the recording
                // screen's Stop (shared bounds, LocalSharedScopes), and back.
                val screen = when {
                    firstRun == true && state == null -> "first"
                    state != null && showLive -> "live"
                    review != null -> "review"
                    settings -> "settings"
                    else -> "notebooks"
                }
                androidx.compose.animation.SharedTransitionLayout {
                androidx.compose.animation.AnimatedContent(
                    screen, label = "screen",
                    // Eased cross-fade, no spring anywhere (the default size change springs: a bounce).
                    transitionSpec = {
                        // Only the first screen and the recording screen fade into each other (Record becomes Stop);
                        // every other change is immediate, as before.
                        val recording = setOf(initialState, targetState) == setOf("notebooks", "live")
                        if (!recording) return@AnimatedContent androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        val ease = androidx.compose.animation.core.FastOutSlowInEasing
                        val millis = com.chencohensolution.transcriptor.ui.MORPH_MILLIS
                        // The screen going away stays whole underneath while the new one fades in over it: no moment
                        // where both are half see-through (the window showing between them: a blink).
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(millis, easing = ease)) togetherWith
                            androidx.compose.animation.ExitTransition.KeepUntilTransitionsFinished)
                            .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.tween(millis, easing = ease) })
                    },
                ) { shown ->
                androidx.compose.runtime.CompositionLocalProvider(com.chencohensolution.transcriptor.ui.LocalSharedScopes provides com.chencohensolution.transcriptor.ui.SharedScopes(this@SharedTransitionLayout, this@AnimatedContent)) {
                when {
                    shown == "first" -> FirstRun(onDone = { firstRun = false })
                    shown == "live" && state != null -> {
                        BackHandler { showLive = false }
                        val advancedMode = remember { Recording.settings.advancedMode }
                        // A location switch turned on without the permission: asked for, then turned on when granted.
                        var askedFor by remember { mutableStateOf<com.chencohensolution.transcriptor.ui.PlaceSwitch?>(null) }
                        val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
                            val which = askedFor
                            askedFor = null
                            if (which != null && locationAccess(this@MainActivity) != com.chencohensolution.transcriptor.ui.LocationAccess.None) placeSwitchNow(which, true)
                            else android.widget.Toast.makeText(this@MainActivity, R.string.glance_location_denied, android.widget.Toast.LENGTH_LONG).show()
                        }
                        val placeSwitch = { which: com.chencohensolution.transcriptor.ui.PlaceSwitch, on: Boolean ->
                            if (on && locationAccess(this@MainActivity) == com.chencohensolution.transcriptor.ui.LocationAccess.None) {
                                askedFor = which
                                askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            } else placeSwitchNow(which, on)
                        }
                        // Settings › Advanced; read when the screen opens (Settings is another screen).
                        val meterStyle = remember { Recording.settings.processorMeters }
                        val stopTwice = remember { Recording.settings.stopTwice }
                        val meters = rememberProcessorMeters(meterStyle)
                        val levelMeter = remember { Recording.settings.levelMeter }
                        // The frequency bands cost a small FFT: worked out only while this screen shows them.
                        DisposableEffect(levelMeter) {
                            Recording.session.meterBands = levelMeter.needsBands
                            onDispose { Recording.session.meterBands = false }
                        }
                        LectureScreen(
                            state.copy(meterStyle = meterStyle, meters = meters.now, meterHistory = meters.history, stopTwice = stopTwice, levelMeter = levelMeter),
                            LiveLectureActions(
                                back = { showLive = false },
                                pauseOrResume = Recording.session::pauseOrResume,
                                stop = { openAfterStop = true; RecordingService.stop(this@MainActivity) },
                                addNote = Recording.session::addNote,
                                mark = Recording.session::mark,
                                setTranscribing = { on -> lifecycleScope.launch(Dispatchers.Default) { Recording.session.setTranscribing(on) } },
                                renameSpeaker = Recording.session::renameSpeaker,
                                editLine = Recording.session::editLine,
                            ),
                            place = Recording.placeStatus.collectAsStateWithLifecycle().value,
                            placeActions = PlaceActions(
                                keepRecording = { RecordingService.keepRecording(this@MainActivity) },
                                stopNow = { openAfterStop = true; RecordingService.stop(this@MainActivity) },
                                cancel = { RecordingService.cancelPlaceStop(this@MainActivity) },
                                here = { RecordingService.placeHere(this@MainActivity) },
                                set = { which, on -> placeSwitch(which, on) },
                                backHere = {
                                    placeSwitch(com.chencohensolution.transcriptor.ui.PlaceSwitch.Back, true)
                                    android.widget.Toast.makeText(this@MainActivity, R.string.glance_back_set, android.widget.Toast.LENGTH_SHORT).show()
                                },
                                stops = advancedMode,
                            ),
                        )
                    }
                    shown == "review" && review != null -> {
                        BackHandler { Recording.review.close() }
                        val r = Recording.review
                        val library by Recording.library.library.collectAsStateWithLifecycle()
                        // The open moment isn't touched by the list's own label changes (it saves itself): its
                        // labels are set through its screen's controller, then the list is read again.
                        val setLabels = { ids: List<String> ->
                            r.setLabels(ids, labelNames(ids, Recording.library.library.value.labels))
                            lifecycleScope.launch(Dispatchers.IO) { Recording.library.reload() }
                            Unit
                        }
                        ReviewScreen(
                            review,
                            allLabels = library.labels,
                            actions = ReviewActions(
                                info = r::info,
                                back = r::close, playPause = r::playPause, seek = r::seek, playLine = r::playLine,
                                cycleSpeed = r::cycleSpeed, editLine = r::editLine, setLineSpeaker = r::setLineSpeaker,
                                renameSpeaker = { id, name -> lifecycleScope.launch(Dispatchers.IO) { r.renameSpeaker(id, name) } },
                                namePerson = { id, name -> lifecycleScope.launch(Dispatchers.IO) { r.namePerson(id, name) } },
                                mergeSpeakers = { from, into -> lifecycleScope.launch(Dispatchers.IO) { r.mergeSpeakers(from, into) } },
                                playPerson = r::playPerson,
                                playOnly = r::playOnly,
                                shareText = {
                                    r.text()?.let { text ->
                                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, review.lecture.title), null))
                                    }
                                },
                                rename = { title -> r.rename(title); lifecycleScope.launch(Dispatchers.IO) { Recording.library.reload() } },
                                setLabels = setLabels,
                                newLabel = { name -> runBlocking(Dispatchers.IO) { Recording.library.newLabel(name).id } },
                                renameLabel = { id, name ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        Recording.library.renameLabel(id, name)
                                        withContext(Dispatchers.Main) { r.lecture?.let { setLabels(it.labels) } }
                                    }
                                },
                                deleteLabel = { id ->
                                    setLabels(review.lecture.labels - id)
                                    lifecycleScope.launch(Dispatchers.IO) { Recording.deleteLabel(id) }
                                },
                                findSpeakers = { RecordingService.findSpeakers(this@MainActivity, review.lecture) },
                                tuneSpeakers = r::tuneSpeakers, applySpeakerTuning = r::applySpeakerTuning,
                                improve = { model, speakersFirst -> RecordingService.refine(this@MainActivity, review.lecture, model, speakersFirst) },
                                getModel = { id ->
                                    lifecycleScope.launch(Dispatchers.IO) { Recording.modelLibrary.all.firstOrNull { it.id == id }?.let { Recording.models.download(it) } }
                                },
                                switchVersion = r::switchTo,
                                version = r::version,
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
                    shown == "settings" -> {
                        BackHandler { settings = false }
                        Settings(onBack = { settings = false }, chooseFolder = chooseFolder)
                    }
                    shown == "notebooks" -> Notebooks(
                        // On the way to the recording screen the Record bar stays (its button morphs into Stop); the
                        // banner back to the moment shows only while the recording screen is closed.
                        recordingNow = state?.takeIf { !showLive }?.let { RecordingNow(it.lecture.labels, it.lecture.title, it.elapsedMillis, it.lecture.id) },
                        working = working,
                        onRecord = { labels, language, mode -> startLecture(labels, language, mode); showLive = true },
                        onBackToRecording = { showLive = true },
                        onSettings = { settings = true },
                        chooseFolder = chooseFolder,
                        place = notebooksPlace,
                    )
                }
                }
                }
                }
              }
            }
        }
    }

    @Composable
    private fun Notebooks(
        recordingNow: RecordingNow?, working: Map<String, Float>,
        onRecord: (labels: List<String>, language: String, mode: RecordMode) -> Unit, onBackToRecording: () -> Unit, onSettings: () -> Unit,
        chooseFolder: () -> Unit, place: com.chencohensolution.transcriptor.ui.NotebooksPlace,
    ) {
        val library by Recording.library.library.collectAsStateWithLifecycle()
        val server by Recording.backup.state.collectAsStateWithLifecycle()
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
        var recordIn by remember { mutableStateOf<Triple<List<String>, String, RecordMode>?>(null) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val (labels, language, mode) = recordIn ?: return@rememberLauncherForActivityResult
            if (granted[Manifest.permission.RECORD_AUDIO] == true) onRecord(labels, language, mode)
        }
        // The Record bar's Live, After or Only record, as last picked.
        val recordMode = remember { Recording.settings.recordMode }
        val recordMusic = remember { Recording.settings.recordMusic }
        // Advanced mode: the Record bar also offers the quality and battery presets.
        val advanced = remember { Recording.settings.advancedMode }
        var choice by remember { mutableStateOf(if (advanced) Recording.settings.recordingChoice else null) }
        var active by remember { mutableStateOf(Recording.settings.activeProfile) }
        val musicKey = com.chencohensolution.transcriptor.settings.QualityProfile.Music
        var musicChoice by remember { mutableStateOf(if (advanced) Recording.settings.recordingChoice(musicKey) else null) }
        var saving by remember { mutableStateOf(Recording.settings.powerSaving) }
        var musicSaving by remember { mutableStateOf(Recording.settings.savingFor(musicKey)) }
        var transcribeSaving by remember { mutableStateOf(Recording.settings.transcribeSaving) }
        // Read again when the Record bar's Stop by place or Path row changes.
        var placeTick by remember { mutableStateOf(0) }
        val io = { work: suspend () -> Unit -> lifecycleScope.launch(Dispatchers.IO) { work() }; Unit }
        NotebooksScreen(
            notebooksState(library, now, Recording.utcOffset, recordingNow, working).copy(recordMode = recordMode, recordMusic = recordMusic, recordingChoice = choice, activeProfile = active, musicChoice = musicChoice, powerSaving = saving, musicPowerSaving = musicSaving, transcribeSaving = transcribeSaving,
                activeSettings = remember(placeTick, active, choice) { choice?.let { Recording.settings.profile(active) } },
                musicSettings = remember(placeTick, musicChoice) { musicChoice?.let { Recording.settings.profile(musicKey) } },
                location = remember(placeTick) { Recording.settings.location.takeIf { it.wantsLocation } }),
            NotebooksActions(
                openLecture = ::openLecture,
                setRecordMode = { mode -> io { Recording.settings.recordMode = mode } },
                // On this thread, as the quality is: the two are set together when a quality chip is tapped.
                setRecordMusic = { on -> Recording.settings.recordMusic = on },
                setQuality = { profile ->
                    Recording.settings.activeProfile = profile
                    active = profile
                    choice = Recording.settings.recordingChoice
                },
                setTranscribeSaving = { on -> Recording.settings.transcribeSaving = on; transcribeSaving = on },
                setPlace = { stop, path, music ->
                    val key = if (music) musicKey else active
                    val p = Recording.settings.profile(key)
                    Recording.settings.setProfile(key, p.copy(stopByPlace = stop ?: p.stopByPlace, savePath = path ?: p.savePath))
                    placeTick++
                },
                setPower = { preset, music ->
                    val on = preset == com.chencohensolution.transcriptor.settings.PowerPreset.Low
                    // Music's own mode with Music picked, else the shared one.
                    if (music) Recording.settings.setProfile(musicKey, Recording.settings.profile(musicKey).copy(wavFirst = on))
                    else Recording.settings.powerSaving = on
                    saving = Recording.settings.powerSaving
                    musicSaving = Recording.settings.savingFor(musicKey)
                    choice = Recording.settings.recordingChoice
                    musicChoice = Recording.settings.recordingChoice(musicKey)
                },
                openLectureAt = { id, at -> openLecture(id, at) },
                search = { query -> withContext(Dispatchers.IO) { Recording.migrateLibrary(); Recording.library.search(query) } },
                record = { labels, language, mode ->
                    recordIn = Triple(labels, language, mode)
                    permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                },
                backToRecording = onBackToRecording,
                // Made here, not in the background: the sheet that asked puts the new label on its moments at once.
                newLabel = { name -> runBlocking(Dispatchers.IO) { Recording.library.newLabel(name).id } },
                setLabels = { ids, add, remove -> io { Recording.setLabels(ids, add, remove) } },
                renameLabel = { id, name -> io { Recording.library.renameLabel(id, name) } },
                deleteLabel = { id -> io { Recording.deleteLabel(id) } },
                setLabelLocked = { id, locked -> io { Recording.setLabelLocked(id, locked) } },
                settings = onSettings,
                chooseFolder = chooseFolder,
                keepInApp = { asked = true; io { Recording.settings.lectureFolderAsked = true } },
                delete = { ids -> io { Recording.delete(ids) } },
                setTucked = { ids, tucked -> lifecycleScope.launch(Dispatchers.IO) { Recording.setTucked(ids, tucked) } },
                revealTucked = ::askPhoneLock,
                makePrivate = { ids ->
                    val (done, failed) = withContext(Dispatchers.IO) { Recording.makePrivate(ids) }
                    PrivateOutcome(
                        done, notPaired = failed == PrivateResult.NotPaired,
                        failed = when (failed) {
                            is PrivateResult.NotOnServer -> failed.reason
                            PrivateResult.Busy -> getString(R.string.notebooks_private_busy)
                            else -> null
                        },
                    )
                },
            ),
            notice,
            paired = server.paired != null,
            place = place,
            opening = opening.value != null,
        )
    }

    /**
     * Asks for the phone's lock (fingerprint, face or the screen lock) before tucked moments are shown, then calls
     * [onUnlocked]. A phone with no lock shows them at once.
     */
    private fun askPhoneLock(onUnlocked: () -> Unit) {
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) return onUnlocked()
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.notebooks_unlock_tucked))
            .setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(
            android.os.CancellationSignal(), mainExecutor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = onUnlocked()
            },
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

    /**
     * Opens a recorded moment, under its labels' names. One of the server's recordings becomes a moment here first; its
     * audio stays on the server and plays as it streams.
     */
    private fun openLecture(lectureId: String, atMillis: Long? = null) {
        if (opening.value != null) return
        opening.value = lectureId
        lifecycleScope.launch {
            try {
                // A recording only on the server can take a few seconds to become a moment here (the list's page
                // still on its way): tried again for a while, under the spinner, before saying it failed.
                var opened = false
                for (attempt in 0 until 8) {
                    if (attempt > 0) delay(750)
                    val id = withContext(Dispatchers.IO) { Recording.momentsHere(listOf(lectureId)) }.first()
                    val library = Recording.library.library.value
                    val labels = library.moments.firstOrNull { it.id == id }?.labels.orEmpty()
                    opened = Recording.review.open(id, labelNames(labels, library.labels), atMillis)
                    if (opened) break
                    android.util.Log.w("MainActivity", "opening $lectureId ($id), try ${attempt + 1}: no such moment here yet")
                }
                if (!opened) {
                    Toast.makeText(this@MainActivity, getString(R.string.bring_failed, getString(R.string.bring_not_on_server)), Toast.LENGTH_LONG).show()
                }
            } finally {
                opening.value = null
            }
        }
    }

    /** Records a moment titled with today's date, with these [labels] (maybe none), in [language]. */
    private fun startLecture(labels: List<String>, language: String, mode: RecordMode) {
        val title = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        RecordingService.start(this, labels, labelNames(labels, Recording.library.library.value.labels), language, title, mode)
    }

    companion object {
        /** Opens on the moment recording now, if one is (else on the moments). */
        const val EXTRA_SHOW_RECORDING = "showRecording"
    }
    /** A location switch of the recording screen, to the recording. */
    private fun placeSwitchNow(which: com.chencohensolution.transcriptor.ui.PlaceSwitch, on: Boolean) {
        RecordingService.setPlace(
            this, when (which) {
                com.chencohensolution.transcriptor.ui.PlaceSwitch.Leave -> RecordingService.PLACE_LEAVE
                com.chencohensolution.transcriptor.ui.PlaceSwitch.Back -> RecordingService.PLACE_BACK
                com.chencohensolution.transcriptor.ui.PlaceSwitch.Path -> RecordingService.PLACE_PATH
            }, on,
        )
    }
}

/** Lectures recorded as WAV and waiting for FLAC or AAC: encoded on launch, or left to [EncodeJob] when that waits for the charger. */
@Composable
private fun CompressPending() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val pending = withContext(Dispatchers.IO) { Recording.compression.pending() }
        if (pending.isEmpty() || Recording.compressing.value != null) return@LaunchedEffect
        val (percent, charging) = Recording.battery(context)
        // "Encode when charging": the job runs once the phone is on the charger, the app open or not.
        // Moments wait by their own profile's mode (Music has its own): any that may go now starts the service.
        if (pending.any { Recording.settings.encodeAllowed(it, charging, percent) }) RecordingService.compressPending(context) else EncodeJob.schedule(context)
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
                    npu = it.kind.onNpu, strength = ModelChoices.strength(it),
                )
            },
            improveDefault = default?.id,
            improveToGet = toGet?.let { ModelToGet(it.id, it.name, it.sizeBytes) },
        )
    }
}
