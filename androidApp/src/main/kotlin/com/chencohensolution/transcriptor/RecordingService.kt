package com.chencohensolution.transcriptor

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
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
                startForeground(ID, recordingNotification(paused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                session.start(
                    intent.getStringArrayExtra(EXTRA_LABELS)?.toList().orEmpty(), intent.getStringExtra(EXTRA_LABEL_NAMES).orEmpty(),
                    intent.getStringExtra(EXTRA_LANGUAGE)!!, intent.getStringExtra(EXTRA_TITLE)!!,
                )
            }
            ACTION_PAUSE -> session.pauseOrResume()
            ACTION_STOP -> scope.launch {
                // Returns once live transcription has finished the last lines.
                val lecture = session.stop()?.also { Recording.lastLecture.value = it }
                val improve = lecture?.takeIf { it.segments.isNotEmpty() && Recording.settings.autoImprove }?.let { Recording.improveModelFor(it.language) }
                // Improving on its own waits for the charger.
                if (lecture != null && improve != null && !charging()) Recording.settings.addImprovePending(lecture.id)
                val then = improve?.takeIf { charging() }?.let { model -> { done: Lecture -> refine(done, model.id) } }
                when {
                    lecture != null && lecture.segments.isNotEmpty() && Recording.canFindSpeakers && Recording.settings.findSpeakersOnStop -> findSpeakers(lecture, then)
                    lecture != null && then != null -> then(lecture)
                    else -> compressPending()
                }
            }
            ACTION_REFINE -> {
                val lecture = Recording.store.load(intent.getStringExtra(EXTRA_LECTURE)!!)
                val model = intent.getStringExtra(EXTRA_MODEL)!!
                val speakersFirst = intent.getBooleanExtra(EXTRA_SPEAKERS_FIRST, false) && Recording.canFindSpeakers
                if (lecture != null && !session.isRecording && !compressing && Recording.refineModels(lecture.language).any { it.id == model }) {
                    if (speakersFirst) findSpeakers(lecture) { done -> refine(done, model) } else refine(lecture, model)
                } else {
                    startForeground(ID, progressNotification(R.string.refining, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
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
                    startForeground(ID, progressNotification(R.string.finding_speakers, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                    finish()
                }
            }
            ACTION_COMPRESS -> compressPending()
            ACTION_IMPROVE_PENDING -> {
                startForeground(ID, progressNotification(R.string.refining, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
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
        startForeground(ID, progressNotification(R.string.finding_speakers, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        scope.launch(Dispatchers.IO) {
            var shown = -1
            Recording.findingSpeakers.value = lecture.id to 0f
            runCatching {
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
        compressing = true
        startForeground(ID, progressNotification(R.string.refining, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        scope.launch(Dispatchers.IO) {
            var shown = -1
            Recording.refining.value = lecture.id to 0f
            runCatching {
                Recording.refine(lecture, modelId, kept = { launch(Dispatchers.Main) { if (Recording.review.lecture?.id == lecture.id) Recording.review.reload() } }) { p ->
                    Recording.refining.value = lecture.id to p
                    val percent = (p * 100).toInt()
                    if (percent != shown) { shown = percent; notify(progressNotification(R.string.refining, p)) }
                }
            }.onSuccess { done ->
                if (Recording.lastLecture.value?.id == done.id) Recording.lastLecture.value = done
                launch(Dispatchers.Main) { if (Recording.review.lecture?.id == done.id) Recording.review.reload() }
            }.onFailure { android.util.Log.e("RecordingService", "refine pass failed", it) }
            Recording.settings.removeImprovePending(lecture.id)
            Recording.refining.value = null
            compressing = false
            launch(Dispatchers.Main) { if (!improveNextPending()) compressPending() }
        }
    }

    /** The phone is on the charger. */
    private fun charging() = getSystemService(android.os.BatteryManager::class.java).isCharging

    /**
     * Improves the next lecture waiting for the charger, if the phone charges and its language has a model; drops
     * ones that are gone or can't be improved. False when there is none to start.
     */
    private fun improveNextPending(): Boolean {
        if (!charging() || Recording.session.isRecording) return false
        for (lectureId in Recording.settings.improvePending) {
            val lecture = Recording.store.load(lectureId)
            val model = lecture?.let { Recording.improveModelFor(it.language) }
            if (lecture == null || model == null || lecture.refinedWith != null) { Recording.settings.removeImprovePending(lectureId); continue }
            refine(lecture, model.id)
            return true
        }
        return false
    }

    private fun compressPending() {
        if (compressing) return
        val pending = Recording.compression.pending()
        if (pending.isEmpty()) { finish(); return }
        compressing = true
        startForeground(ID, progressNotification(R.string.compressing_flac, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        scope.launch(Dispatchers.IO) {
            for (lecture in pending) {
                runCatching {
                    var shown = -1
                    Recording.compression.compress(lecture) { p ->
                        Recording.compressing.value = lecture.id to p
                        val percent = (p * 100).toInt()
                        if (percent != shown) { shown = percent; notify(progressNotification(R.string.compressing_flac, p)) }
                    }
                }.onSuccess { done ->
                    if (Recording.lastLecture.value?.id == done.id) Recording.lastLecture.value = done
                }
            }
            Recording.compressing.value = null
            compressing = false
            launch(Dispatchers.Main) { finish() }
        }
    }

    private fun finish() {
        if (Recording.session.isRecording || compressing) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(ID, notification)

    private fun recordingNotification(paused: Boolean): Notification {
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
            .addAction(action(ACTION_PAUSE, if (paused) R.string.lecture_resume else R.string.lecture_pause))
            .addAction(action(ACTION_STOP, R.string.lecture_stop))
            .build()
    }

    private fun finishingNotification() = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.lecture_finishing))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .build()

    private fun progressNotification(title: Int, progress: Float) = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(title))
        .setProgress(100, (progress * 100).toInt(), false)
        .setOngoing(true)
        .build()

    private fun action(action: String, label: Int) = Notification.Action.Builder(
        null, getString(label),
        PendingIntent.getService(this, action.hashCode(), Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    companion object {
        private const val CHANNEL = "recording"
        private const val ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_STOP = "stop"
        private const val ACTION_COMPRESS = "compress"
        private const val ACTION_SPEAKERS = "speakers"
        private const val ACTION_REFINE = "refine"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_LABEL_NAMES = "labelNames"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LECTURE = "lecture"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_SPEAKERS_FIRST = "speakersFirst"
        private const val ACTION_IMPROVE_PENDING = "improvePending"

        /** Records a moment with these [labels] (ids; [labelNames] for its screen) in [language]. */
        fun start(context: Context, labels: List<String>, labelNames: String, language: String, title: String) = context.startForegroundService(
            Intent(context, RecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_LABELS, labels.toTypedArray()).putExtra(EXTRA_LABEL_NAMES, labelNames)
                .putExtra(EXTRA_LANGUAGE, language).putExtra(EXTRA_TITLE, title),
        )

        fun stop(context: Context) =
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))

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
