package com.chencohensolution.transcriptor

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.chencohensolution.transcriptor.notebook.Library
import com.chencohensolution.transcriptor.notebook.labelNames
import com.chencohensolution.transcriptor.notebook.notebooksState
import com.chencohensolution.transcriptor.recording.LiveLectureState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The Quick Settings tile: Record, and Stop while a moment records (the tile on). Stop asks for a second tap, as in the
 * app. Recording starts through [RecordFromTileActivity]: Android lets an app start the microphone only from the
 * foreground, and a tile's tap alone doesn't count.
 */
class RecordTileService : TileService() {
    private var scope: CoroutineScope? = null

    /** Stop was tapped once: a second tap within [ARM_MILLIS] stops. */
    private var armed: Job? = null

    override fun onStartListening() {
        scope?.cancel()
        scope = MainScope().also { s -> s.launch { Recording.session.state.collect { show(it) } } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        armed = null
    }

    override fun onClick() {
        val state = Recording.session.state.value
        when {
            state == null -> {
                val start = PendingIntent.getActivity(
                    this, 0, Intent(this, RecordFromTileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                // Locked: the phone is unlocked first, so nobody records on a phone that isn't theirs.
                if (isLocked) unlockAndRun { startActivityAndCollapse(start) } else startActivityAndCollapse(start)
            }
            state.finishing -> Unit
            armed?.isActive == true -> { armed?.cancel(); armed = null; RecordingService.stop(this) }
            else -> {
                armed = scope?.launch { delay(ARM_MILLIS); armed = null; show(Recording.session.state.value) }
                show(state)
            }
        }
    }

    private fun show(state: LiveLectureState?) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        if (state == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.notebooks_record)
            tile.subtitle = null
        } else {
            tile.state = if (state.finishing) Tile.STATE_UNAVAILABLE else Tile.STATE_ACTIVE
            tile.label = getString(if (state.finishing) R.string.recording_now else R.string.lecture_stop)
            tile.subtitle = getString(
                when {
                    state.finishing -> R.string.tile_finishing
                    armed?.isActive == true -> R.string.tile_stop_again
                    state.paused -> R.string.lecture_paused
                    else -> R.string.recording_now
                },
            )
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    private companion object {
        const val ARM_MILLIS = 4_000L
    }
}

/**
 * Starts a moment from the tile, as the Record bar would now: its guessed labels and language, its Live or After, and
 * the recording format and notification settings. Shows nothing and finishes; it exists so the microphone is started
 * from the foreground. Without the microphone permission it opens the app instead, whose Record asks for it.
 */
class RecordFromTileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Recording.session.isRecording) { finish(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        lifecycleScope.launch {
            val (library, live) = withContext(Dispatchers.IO) {
                // A fresh process (the app not opened since): the moments aren't read yet, and the guess needs them.
                if (Recording.library.library.value == Library()) Recording.library.reload()
                Recording.library.library.value to Recording.settings.recordLive
            }
            val next = notebooksState(library, System.currentTimeMillis(), Recording.utcOffset)
            val labels = next.guess?.labels.orEmpty().filter { id -> library.labels.any { it.id == id } }
            if (!Recording.session.isRecording) {
                val title = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
                RecordingService.start(this@RecordFromTileActivity, labels, labelNames(labels, library.labels), next.language, title, live)
            }
            finish()
        }
    }
}
