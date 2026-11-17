package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.settings.allows
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.recording.ImproveAfterStop
import com.chencohensolution.transcriptor.settings.RecordMode
import com.chencohensolution.transcriptor.speech.RefinePass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps [Recording.session] recording with the screen off (a foreground service of the microphone type), with
 * Pause/Resume and Stop in its notification. After Stop, a lecture recorded with "WAV, then FLAC" is compressed here,
 * as a media-processing foreground service, and so is any left pending by an earlier interruption.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var compressing = false

    /** Settings › Recordings › Hide the recording notification, read when a recording starts. */
    private var quiet = false

    /** Only record: the moment recording now isn't transcribed after it stops; it waits for "Transcribe now". */
    private var onlyRecord = false

    /** Stop by place and the path for the moment recording now; null when neither is on for it. */
    private var place: com.chencohensolution.transcriptor.location.MomentLocation? = null
    private var feed: LocationFeed? = null
    private var placeTicker: kotlinx.coroutines.Job? = null
    /** The countdown's mark is on the moment. */
    private var countdownMarked = false

    /** The moment whose path goes on after its audio stopped ("Keep saving the path after stopping"); null otherwise. */
    private var keepingFor: String? = null

    /** Media processing, and location while a path goes on after Stop (the service mustn't lose it meanwhile). */
    private val processingType get() =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or (if (keepingFor != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        // The quiet one (Settings › Recordings): the lowest importance, never on the lock screen, no badge.
        // Stop by place's countdown: it must be seen and felt, even with the recording's notification quiet.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(PLACE_CHANNEL, getString(R.string.place_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            },
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL, getString(R.string.recording_quiet_channel), NotificationManager.IMPORTANCE_MIN).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            },
        )
        scope.launch {
            Recording.session.state.filterNotNull().map { it.paused to it.finishing }.distinctUntilChanged().collect { (paused, finishing) ->
                notify(if (finishing) finishingNotification() else recordingNotification(paused))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = Recording.session
        when (intent?.action) {
            ACTION_START -> if (!session.isRecording) {
                // A path still going on from the last moment ends here: this one has its own.
                if (keepingFor != null) endKeeping()
                quiet = Recording.settings.quietRecording
                val mode = RecordMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: RecordMode.Live.name)
                onlyRecord = mode == RecordMode.Only || mode == RecordMode.Music
                val located = placeFor(mode)
                try {
                    try {
                        startForeground(
                            ID, recordingNotification(paused = false),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (located != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0),
                        )
                    } catch (e: SecurityException) {
                        // Location wasn't allowed for this start (from the tile, say): the moment records without it.
                        if (located == null) throw e
                        android.util.Log.w("RecordingService", "location refused for this recording", e)
                        startForeground(ID, recordingNotification(paused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                        place = null
                    }
                } catch (e: RuntimeException) {
                    // From the tile, Android didn't count it as in use (SecurityException, or the start not allowed):
                    // started again from the foreground instead.
                    if (!intent.getBooleanExtra(EXTRA_FROM_TILE, false)) throw e
                    android.util.Log.w("RecordingService", "microphone refused from the tile, through an activity", e)
                    startActivity(Intent(this, RecordFromTileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    // Started as a foreground service: it must be one before stopping (media processing needs no "in use").
                    if (!compressing) {
                        runCatching { startForeground(ID, progressNotification(R.string.recording_now, 0f), processingType) }
                        finish()
                    }
                    return START_NOT_STICKY
                }
                session.start(
                    intent.getStringArrayExtra(EXTRA_LABELS)?.toList().orEmpty(), intent.getStringExtra(EXTRA_LABEL_NAMES).orEmpty(),
                    intent.getStringExtra(EXTRA_LANGUAGE)!!, intent.getStringExtra(EXTRA_TITLE)!!,
                    transcribeLive = mode == RecordMode.Live, transcribeAfter = !onlyRecord, music = mode == RecordMode.Music,
                )
                if (place != null) startPlace()
            }
            ACTION_KEEP -> place?.let { p ->
                Recording.placeStatus.value = p.keepRecording(System.currentTimeMillis())
                countdownMarked = false
                getSystemService(NotificationManager::class.java).cancel(PLACE_ID)
            }
            ACTION_PLACE_CANCEL -> place?.let { p -> Recording.placeStatus.value = p.cancel(System.currentTimeMillis()) }
            ACTION_PLACE_HERE -> place?.let { p -> Recording.placeStatus.value = p.reanchor(System.currentTimeMillis()) }
            ACTION_PATH_STOP -> if (keepingFor != null) endKeeping()
            ACTION_PAUSE -> session.pauseOrResume()
            ACTION_MARK -> session.mark()
            ACTION_STOP -> scope.launch {
                // The live model chosen didn't fit in the free memory at the start: improved with it once this one is unloaded.
                val skipped = session.state.value?.skippedForMemory != null
                // Returns once live transcription has finished the last lines (and closed the live model).
                val located = place
                val settings = Recording.settings.location
                // "Keep saving the path after stopping": the path goes on, saved with the moment when it ends.
                val keep = located != null && settings.keepAfterStop &&
                    located.keepGoing(System.currentTimeMillis(), session.positionMillis, settings.keep, settings.places)
                val path = (if (keep) located?.keptPath() else located?.path(audioEndMillis = session.positionMillis))?.takeIf { !it.isEmpty }
                val lecture = session.stop(located?.placeStop(), hasPath = path != null || keep)?.also { Recording.lastLecture.value = it }
                if (lecture != null && path != null) runCatching { Recording.store.savePath(lecture, path) }
                if (keep && lecture != null) startKeeping(lecture.id) else stopPlace()
                val model = lecture?.let { Recording.improveModelFor(it.language) }
                // Only record: nothing runs on it but the encoding; "Transcribe now" on the moment when wanted.
                if (onlyRecord && lecture != null && lecture.pendingTranscript) { if (!improveNextPending()) compressPending(); return@launch }
                // Live transcription was off for part of it: those stretches are transcribed after, as an After moment
                // is (Settings' "Transcribe After moments": now, or on the charger with enough battery).
                val gaps = lecture?.liveOff?.isNotEmpty() == true && !onlyRecord && model != null
                val after = if (gaps) { if (transcribeAllowed()) ImproveAfterStop.Now else ImproveAfterStop.WhenCharging }
                    else ImproveAfterStop.of(lecture?.segments?.isNotEmpty() == true, model != null, skipped, Recording.settings.autoImprove, charging())
                if (lecture != null && after == ImproveAfterStop.WhenCharging) Recording.settings.addImprovePending(lecture.id)
                val then = model?.takeIf { after == ImproveAfterStop.Now }?.let { m -> { done: Lecture -> refine(done, m.id) } }
                when {
                    // Only recorded (After, or no live model): transcribed now when Settings says right after Stop, or
                    // on the charger with 30% or more; else it waits for that (or for "Transcribe now" on the moment).
                    lecture != null && lecture.pendingTranscript -> {
                        if (model != null && transcribeAllowed()) refine(lecture, model.id)
                        else {
                            if (model != null) Recording.settings.addImprovePending(lecture.id)
                            if (!improveNextPending()) compressPending()
                        }
                    }
                    lecture != null && lecture.segments.isNotEmpty() && Recording.canFindSpeakers && Recording.settings.findSpeakersOnStop -> findSpeakers(lecture, then)
                    lecture != null && then != null -> then(lecture)
                    else -> if (!improveNextPending()) compressPending()
                }
            }
            ACTION_REFINE -> {
                val lecture = Recording.store.load(intent.getStringExtra(EXTRA_LECTURE)!!)
                val model = intent.getStringExtra(EXTRA_MODEL)!!
                val speakersFirst = intent.getBooleanExtra(EXTRA_SPEAKERS_FIRST, false) && Recording.canFindSpeakers
                if (lecture != null && !session.isRecording && !compressing && Recording.refineModels(lecture.language).any { it.id == model }) {
                    if (speakersFirst) findSpeakers(lecture) { done -> refine(done, model) } else refine(lecture, model)
                } else {
                    startForeground(ID, progressNotification(R.string.refining, 0f), processingType)
                    finish()
                }
            }
            ACTION_SPEAKERS -> {
                val wanted = intent.getStringExtra(EXTRA_LECTURE)
                val lecture = if (wanted == null) Recording.lastLecture.value
                    else Recording.store.load(wanted)
                if (lecture != null && !session.isRecording && !compressing) {
                    findSpeakers(lecture)
                } else {
                    // Started as a foreground service: it must be one before stopping.
                    startForeground(ID, progressNotification(R.string.finding_speakers, 0f), processingType)
                    finish()
                }
            }
            ACTION_COMPRESS -> compressPending()
            ACTION_IMPROVE_PENDING -> {
                startForeground(ID, progressNotification(R.string.refining, 0f), processingType)
                if (!session.isRecording && !compressing && !improveNextPending()) finish()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * The speaker pass on [lecture], as a media-processing foreground service; then [then] with the lecture it wrote
     * (the improve pass), else any FLAC compression.
     */
    private fun findSpeakers(lecture: Lecture, then: ((Lecture) -> Unit)? = null) {
        compressing = true
        startForeground(ID, progressNotification(R.string.finding_speakers, 0f), processingType)
        scope.launch(Dispatchers.IO) {
            var shown = -1
            Recording.findingSpeakers.value = lecture.id to 0f
            runCatching {
                Recording.audioHere(lecture)
                Recording.findSpeakers(lecture) { p ->
                    Recording.findingSpeakers.value = lecture.id to p
                    val percent = (p * 100).toInt()
                    if (percent != shown) { shown = percent; notify(progressNotification(R.string.finding_speakers, p)) }
                }
            }.onSuccess { done ->
                if (Recording.lastLecture.value?.id == done.id) Recording.lastLecture.value = done
                launch(Dispatchers.Main) { if (Recording.review.lecture?.id == done.id) Recording.review.reload() }
            }
                .onFailure { android.util.Log.e("RecordingService", "speaker pass failed", it) }
            Recording.findingSpeakers.value = null
            compressing = false
            val next = Recording.store.load(lecture.id) ?: lecture
            launch(Dispatchers.Main) { if (then != null) then(next) else compressPending() }
        }
    }

    /**
     * The improve pass on [lecture], as a media-processing foreground service; then the next lecture waiting to be
     * improved while the phone still charges, else any FLAC compression.
     */
    private fun refine(lecture: Lecture, modelId: String) {
        // A moment only recorded: this pass writes its first transcript, then its speakers are found.
        val transcribing = lecture.pendingTranscript && lecture.segments.isEmpty()
        val title = if (transcribing) R.string.transcribing else R.string.refining
        // Still not enough free memory for its model (other apps): it waits for the charger instead of being dropped.
        val model = Recording.refineModels(lecture.language).firstOrNull { it.id == modelId }
        if (model != null && !Recording.npuGuard.fitsNow(model)) {
            android.util.Log.i("RecordingService", "not enough free memory for ${model.name} now: ${lecture.id} waits for the charger")
            Recording.settings.addImprovePending(lecture.id)
            compressPending()
            return
        }
        compressing = true
        // A call of the phone's: which of how many, and whom it was with (hen, Nov 17, 2026).
        val callLine = if (lecture.callFile != null && transcribing) Recording.backup.state.value.let { s ->
            getString(R.string.calls_notification, s.callsTotal - s.callsToTranscribe + 1, s.callsTotal.coerceAtLeast(1), lecture.title)
        } else null
        fun note(p: Float) = if (callLine != null) progressNotification(callLine, p) else progressNotification(title, p)
        startForeground(ID, note(0f), processingType)
        scope.launch(Dispatchers.IO) {
            var shown = -1
            var speakersNext: Lecture? = null
            Recording.refining.value = lecture.id to 0f
            runCatching {
                // A moment whose audio is on the server: the whole file comes first (it played as it streamed).
                Recording.audioHere(lecture)
                Recording.refine(
                    lecture, modelId,
                    kept = { launch(Dispatchers.Main) { if (Recording.review.lecture?.id == lecture.id) Recording.review.reload() } },
                    progress = { p ->
                        Recording.refining.value = lecture.id to p
                        val percent = (p * 100).toInt()
                        if (percent != shown) { shown = percent; notify(note(p)) }
                    },
                    // A recording started: its live model needs the memory this model holds. Closed now, done after it stops.
                    stop = { Recording.session.isRecording },
                )
            }.onSuccess { done ->
                if (Recording.lastLecture.value?.id == done.id) Recording.lastLecture.value = done
                launch(Dispatchers.Main) { if (Recording.review.lecture?.id == done.id) Recording.review.reload() }
                if (transcribing && done.segments.isNotEmpty() && Recording.canFindSpeakers) speakersNext = done
                // A call of the phone's, transcribed: it may go up now (it waited for its words).
                if (done.callFile != null && !done.pendingTranscript) Recording.callTranscribed(this@RecordingService)
            }.onFailure {
                if (it is RefinePass.Stopped) { Recording.modelReleased(); Recording.improveAfterRecording += lecture.id to modelId }
                else {
                    android.util.Log.e("RecordingService", "refine pass failed", it)
                    // Not taken again in this run: the next call is.
                    if (lecture.callFile != null) callsFailed += lecture.id
                }
            }
            Recording.settings.removeImprovePending(lecture.id)
            Recording.refining.value = null
            compressing = false
            launch(Dispatchers.Main) {
                val speakers = speakersNext
                if (speakers != null && !Recording.session.isRecording) findSpeakers(speakers) { if (!improveNextPending()) compressPending() }
                else if (!improveNextPending()) compressPending()
            }
        }
    }

    /** Calls whose transcribe pass failed while this service runs: left for the next time. */
    private val callsFailed = mutableSetOf<String>()

    /** The phone is on the charger. */
    private fun charging() = getSystemService(android.os.BatteryManager::class.java).isCharging

    /** A moment recorded with "After" may be transcribed now: right after Stop, or on the charger with enough battery. */
    private fun transcribeAllowed(): Boolean {
        val (percent, charging) = Recording.battery(this)
        return Recording.settings.transcribeWhen.allows(charging, percent)
    }

    /**
     * Improves the next lecture whose pass a recording stopped, else the next waiting for the charger, if the phone charges and its language has a model; drops
     * ones that are gone or can't be improved. False when there is none to start.
     */
    private fun improveNextPending(): Boolean {
        if (Recording.session.isRecording) return false
        // First a pass a recording stopped: started again now, charger or not (it was running before the recording).
        while (Recording.improveAfterRecording.isNotEmpty()) {
            val (lectureId, modelId) = Recording.improveAfterRecording.removeAt(0)
            val lecture = Recording.store.load(lectureId) ?: continue
            if (lecture.refinedWith != null || Recording.refineModels(lecture.language).none { it.id == modelId }) continue
            refine(lecture, modelId)
            return true
        }
        val charging = charging()
        for (lectureId in Recording.settings.improvePending) {
            val lecture = Recording.store.load(lectureId)
            val model = lecture?.let { Recording.improveModelFor(it.language) }
            if (lecture == null || model == null || lecture.refinedWith != null) { Recording.settings.removeImprovePending(lectureId); continue }
            // A moment waiting for its transcript follows Settings' "Transcribe After moments"; improving waits for the charger.
            val transcribing = (lecture.pendingTranscript && lecture.segments.isEmpty()) || lecture.liveOff.isNotEmpty()
            if (if (transcribing) !transcribeAllowed() else !charging) continue
            refine(lecture, model.id)
            return true
        }
        // The phone's calls (Show calls in the log), newest first: on the charger with enough battery, or now for
        // Upload now. One with no model for its language is left waiting.
        if (Recording.backup.callMoments) {
            val (percent, plugged) = Recording.battery(this)
            if (Recording.callsNow || (plugged && percent >= com.chencohensolution.transcriptor.settings.ENCODE_MIN_BATTERY)) {
                for (id in Recording.backup.callsWaitingForText()) {
                    if (id in callsFailed) continue
                    val lecture = Recording.store.load(id) ?: continue
                    if (!Recording.store.hasAudio(lecture)) continue
                    val model = Recording.improveModelFor(lecture.language) ?: continue
                    refine(lecture, model.id)
                    return true
                }
            }
            Recording.callsNowDone(this)
        }
        return false
    }

    /** Encodes the lectures waiting for FLAC or AAC, unless the setting waits for the charger ([Recording.encodePending]). */
    private fun compressPending() {
        if (compressing) return
        compressing = true
        startForeground(ID, progressNotification(R.string.compressing_flac, 0f), processingType)
        scope.launch(Dispatchers.IO) {
            var shown = -1
            Recording.encodePending(this@RecordingService) { lecture, p ->
                val percent = (p * 100).toInt()
                if (percent != shown) { shown = percent; notify(progressNotification(if (lecture.pendingAac) R.string.compressing_aac else R.string.compressing_flac, p)) }
            }
            compressing = false
            launch(Dispatchers.Main) { finish() }
        }
    }

    /**
     * Stop by place and the path for a moment starting in [mode]: on when Settings › Advanced › Location has them on,
     * its profile does too, and the phone lets the app know its location. Sets [place]; null when neither is on.
     */
    private fun placeFor(mode: RecordMode): com.chencohensolution.transcriptor.location.MomentLocation? {
        place = null
        val settings = Recording.settings
        val location = settings.location
        if (!location.stopByPlace && !location.savePath) return null
        val profile = settings.profile(if (mode == RecordMode.Music) com.chencohensolution.transcriptor.settings.QualityProfile.Music else settings.activeProfile)
        val canLocate = locationAccess(this) != com.chencohensolution.transcriptor.ui.LocationAccess.None
        val stopOn = location.stopByPlace && profile.stopByPlace
        val m = com.chencohensolution.transcriptor.location.MomentLocation(
            location.copy(stopWhenLeaving = location.stopWhenLeaving && canLocate, stopOnArrive = location.stopOnArrive && canLocate),
            stopOn = stopOn, pathOn = profile.savePath && canLocate,
            motionSensor = getSystemService(android.hardware.SensorManager::class.java)?.getDefaultSensor(android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION) != null,
            startedAtMillis = System.currentTimeMillis(),
            toRecording = { clock -> (Recording.session.positionMillis - (System.currentTimeMillis() - clock)).coerceAtLeast(0) },
        )
        place = m.takeIf { it.active }
        return place?.takeIf { it.needsFixes }
    }

    /** Listens for this moment's fixes and Bluetooth, and moves the countdown on every second. */
    private fun startPlace() {
        val p = place ?: return
        countdownMarked = false
        Recording.placeStatus.value = p.status.value
        val f = LocationFeed(this, onFix = { fix -> onPlace(p.fix(fix)) }, onDevice = { onPlace(p.deviceConnected(System.currentTimeMillis())) })
        feed = f
        f.start(p.needsFixes, Recording.settings.location.device?.takeIf { Recording.settings.location.stopOnDevice })
        f.request(p.status.value.request, p.minutePrecise)
        // Precise while moving: the motion sensor turns GPS on at the first steps.
        if (p.wantsMotion) f.watchMotion { onPlace(p.motion(System.currentTimeMillis())) }
        placeTicker = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                onPlace(p.tick(System.currentTimeMillis()))
            }
        }
    }

    /** The audio of [lectureId] stopped; its path goes on, with a notification to stop it. */
    private fun startKeeping(lectureId: String) {
        keepingFor = lectureId
        Recording.placeStatus.value = null
        // The countdown's stop ended the ticker: the time limit still needs it.
        val p = place
        if (p != null && placeTicker?.isActive != true) placeTicker = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                onPlace(p.tick(System.currentTimeMillis()))
            }
        }
        getSystemService(NotificationManager::class.java).cancel(PLACE_ID)
        getSystemService(NotificationManager::class.java).notify(PATH_ID, pathNotification())
    }

    /** The path after Stop ends: saved with its moment, and location is let go. */
    private fun endKeeping() {
        val id = keepingFor ?: return
        val path = place?.keptPath()
        keepingFor = null
        if (path != null && !path.isEmpty) runCatching { Recording.store.load(id)?.let { Recording.store.savePath(it, path) } }
        // Its note on the server carries the path (Sync location): sent again at the next sync.
        runCatching { Recording.backup.momentSaved(id) }
        stopPlace()
        getSystemService(NotificationManager::class.java).cancel(PATH_ID)
        finish()
    }

    /** "Saving the path", with Stop. */
    private fun pathNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.path_keeping))
        .setContentText(getString(R.string.path_keeping_note))
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .addAction(action(ACTION_PATH_STOP, R.string.path_keeping_stop))
        .build()

    /** Acts on stop by place's state: the countdown's notification and mark, the stop when it runs out. */
    private fun onPlace(status: com.chencohensolution.transcriptor.location.MomentLocation.Status) {
        if (keepingFor != null) {
            feed?.request(status.request, false)
            if (place?.keepingDone(System.currentTimeMillis()) == true) endKeeping()
            return
        }
        Recording.placeStatus.value = status
        feed?.request(status.request, place?.minutePrecise == true)
        when (val w = status.watch) {
            is com.chencohensolution.transcriptor.location.PlaceWatch.State.Counting -> {
                if (!countdownMarked) {
                    countdownMarked = true
                    place?.firedAtRecording()?.let { Recording.session.markAt(it) }
                }
                getSystemService(NotificationManager::class.java).notify(PLACE_ID, countdownNotification(w))
            }
            is com.chencohensolution.transcriptor.location.PlaceWatch.State.Stop -> {
                getSystemService(NotificationManager::class.java).cancel(PLACE_ID)
                if (Recording.session.isRecording && Recording.session.state.value?.finishing != true) {
                    placeTicker?.cancel()
                    onStartCommand(Intent(this, RecordingService::class.java).setAction(ACTION_STOP), 0, 0)
                }
            }
            else -> Unit
        }
    }

    /** Stops listening for this moment's location. */
    private fun stopPlace() {
        placeTicker?.cancel()
        placeTicker = null
        feed?.stop()
        feed = null
        place = null
        Recording.placeStatus.value = null
        getSystemService(NotificationManager::class.java).cancel(PLACE_ID)
    }

    /** "Left the place. The recording stops in 45 s", with Keep recording and Stop now. */
    private fun countdownNotification(w: com.chencohensolution.transcriptor.location.PlaceWatch.State.Counting): Notification {
        val title = when (w.reason) {
            com.chencohensolution.transcriptor.location.StopReason.Left -> getString(R.string.place_left)
            com.chencohensolution.transcriptor.location.StopReason.Arrived -> getString(R.string.place_arrived, w.place?.name ?: "")
            com.chencohensolution.transcriptor.location.StopReason.Device -> getString(R.string.place_device)
        }
        val seconds = ((w.endsAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return Notification.Builder(this, PLACE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(getString(R.string.place_stops_in, seconds))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(action(ACTION_KEEP, R.string.place_keep))
            .addAction(action(ACTION_STOP, R.string.place_stop_now))
            .build()
    }

    private fun finish() {
        if (Recording.session.isRecording || compressing) return
        // The path goes on after Stop: the service stays, showing only that.
        if (keepingFor != null) {
            runCatching { startForeground(ID, pathNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
            getSystemService(NotificationManager::class.java).cancel(PATH_ID)
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (keepingFor != null) {
            val id = keepingFor!!
            place?.keptPath()?.takeIf { !it.isEmpty }?.let { path -> runCatching { Recording.store.load(id)?.let { Recording.store.savePath(it, path) } } }
            keepingFor = null
        }
        stopPlace()
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(ID, notification)

    private fun recordingNotification(paused: Boolean): Notification {
        // Quiet: the app's name and nothing else. The recording goes on; it is paused and stopped from the app.
        if (quiet) return Notification.Builder(this, QUIET_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
        val elapsed = Recording.session.state.value?.elapsedMillis ?: 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Recording.session.state.value?.lecture?.title ?: getString(R.string.app_name))
            .setContentText(getString(if (paused) R.string.lecture_paused else R.string.recording_now))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - elapsed)
            .apply { if (!paused) addAction(action(ACTION_MARK, R.string.glance_mark)) }
            .addAction(action(ACTION_PAUSE, if (paused) R.string.lecture_resume else R.string.lecture_pause))
            .addAction(action(ACTION_STOP, R.string.lecture_stop))
            .build()
    }

    private fun finishingNotification() = if (quiet) recordingNotification(paused = false) else Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.lecture_finishing))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .build()

    private fun progressNotification(title: Int, progress: Float) = progressNotification(getString(title), progress)

    private fun progressNotification(title: String, progress: Float) = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setProgress(100, (progress * 100).toInt(), false)
        .setOngoing(true)
        .build()

    private fun action(action: String, label: Int) = Notification.Action.Builder(
        null, getString(label),
        PendingIntent.getService(this, action.hashCode(), Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    companion object {
        private const val CHANNEL = "recording"
        private const val QUIET_CHANNEL = "recording-quiet"
        private const val ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_MARK = "mark"
        private const val ACTION_STOP = "stop"
        private const val ACTION_KEEP = "placeKeep"
        private const val ACTION_PLACE_CANCEL = "placeCancel"
        private const val ACTION_PLACE_HERE = "placeHere"
        private const val PLACE_CHANNEL = "place-stop"
        private const val PLACE_ID = 2
        private const val PATH_ID = 3
        private const val ACTION_PATH_STOP = "pathStop"
        private const val ACTION_COMPRESS = "compress"
        private const val ACTION_SPEAKERS = "speakers"
        private const val ACTION_REFINE = "refine"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_LABEL_NAMES = "labelNames"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_FROM_TILE = "fromTile"
        private const val EXTRA_LECTURE = "lecture"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_SPEAKERS_FIRST = "speakersFirst"
        private const val ACTION_IMPROVE_PENDING = "improvePending"

        /**
         * Records a moment with these [labels] (ids; [labelNames] for its screen) in [language], transcribed as it
         * records, after it stops, or not until asked ([mode]). [fromTile]: from the Quick Settings tile, in the background.
         */
        fun start(context: Context, labels: List<String>, labelNames: String, language: String, title: String, mode: RecordMode, fromTile: Boolean = false) = context.startForegroundService(
            Intent(context, RecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_LABELS, labels.toTypedArray()).putExtra(EXTRA_LABEL_NAMES, labelNames)
                .putExtra(EXTRA_LANGUAGE, language).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_FROM_TILE, fromTile),
        )

        fun stop(context: Context) =
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))

        /** Stop by place's countdown: keep recording, and don't stop for that reason again in this moment. */
        fun keepRecording(context: Context) =
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_KEEP))

        /** Stop by place does nothing more in this moment. */
        fun cancelPlaceStop(context: Context) =
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_PLACE_CANCEL))

        /** Leaving is measured from here from now on. */
        fun placeHere(context: Context) =
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_PLACE_HERE))

        /** Improves [lecture] with [modelId], after the speaker pass when [speakersFirst]. */
        fun refine(context: Context, lecture: Lecture, modelId: String, speakersFirst: Boolean = false) = context.startForegroundService(
            Intent(context, RecordingService::class.java).setAction(ACTION_REFINE)
                .putExtra(EXTRA_LECTURE, lecture.id).putExtra(EXTRA_MODEL, modelId)
                .putExtra(EXTRA_SPEAKERS_FIRST, speakersFirst),
        )

        /** Improves the lectures waiting for the charger; from the app, while it is in the foreground and charging. */
        fun improvePending(context: Context) =
            context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_IMPROVE_PENDING))

        /** Runs the speaker pass on [lecture] (again), or on [Recording.lastLecture]. */
        fun findSpeakers(context: Context, lecture: Lecture? = null) = context.startForegroundService(
            Intent(context, RecordingService::class.java).setAction(ACTION_SPEAKERS).apply {
                lecture?.let { putExtra(EXTRA_LECTURE, it.id) }
            },
        )

        /** Compresses lectures an interruption left pending; from the app's start, while it is in the foreground. */
        fun compressPending(context: Context) =
            context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_COMPRESS))
    }
}
