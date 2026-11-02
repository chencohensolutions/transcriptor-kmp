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
import com.chencohensolution.transcriptor.notebook.Notebook
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
            Recording.session.state.filterNotNull().map { it.paused }.distinctUntilChanged().collect { paused ->
                notify(recordingNotification(paused))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = Recording.session
        when (intent?.action) {
            ACTION_START -> if (!session.isRecording) {
                startForeground(ID, recordingNotification(paused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                session.start(
                    Notebook(intent.getStringExtra(EXTRA_NOTEBOOK)!!, intent.getStringExtra(EXTRA_NOTEBOOK_TITLE)!!, intent.getStringExtra(EXTRA_LANGUAGE)!!, emptyList()),
                    intent.getStringExtra(EXTRA_TITLE)!!,
                )
            }
            ACTION_PAUSE -> session.pauseOrResume()
            ACTION_STOP -> {
                session.stop()?.let { Recording.lastLecture.value = it }
                compressPending()
            }
            ACTION_COMPRESS -> compressPending()
        }
        return START_NOT_STICKY
    }

    private fun compressPending() {
        if (compressing) return
        val pending = Recording.compression.pending()
        if (pending.isEmpty()) { finish(); return }
        compressing = true
        startForeground(ID, compressNotification(0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        scope.launch(Dispatchers.IO) {
            for (lecture in pending) {
                runCatching {
                    var shown = -1
                    Recording.compression.compress(lecture) { p ->
                        Recording.compressing.value = p
                        val percent = (p * 100).toInt()
                        if (percent != shown) { shown = percent; notify(compressNotification(p)) }
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

    private fun compressNotification(progress: Float) = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.compressing_flac))
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
        private const val EXTRA_NOTEBOOK = "notebook"
        private const val EXTRA_NOTEBOOK_TITLE = "notebookTitle"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, notebook: Notebook, title: String) = context.startForegroundService(
            Intent(context, RecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_NOTEBOOK, notebook.id).putExtra(EXTRA_NOTEBOOK_TITLE, notebook.title)
                .putExtra(EXTRA_LANGUAGE, notebook.language).putExtra(EXTRA_TITLE, title),
        )

        fun stop(context: Context) =
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))

        /** Compresses lectures an interruption left pending; from the app's start, while it is in the foreground. */
        fun compressPending(context: Context) =
            context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_COMPRESS))
    }
}
