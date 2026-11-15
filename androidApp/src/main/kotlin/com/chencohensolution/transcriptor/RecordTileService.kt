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
import com.chencohensolution.transcriptor.settings.RecordMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The Quick Settings tile: Record, and Stop while a moment records (the tile on); one tap each, or two for Stop when Settings asks. Record starts the recording service in the background; nothing opens.
 */
class RecordTileService : TileService() {
    private var scope: CoroutineScope? = null
    private val clicks = MainScope()

    /** Stop was tapped once, with Settings' two taps on: a second tap within [ARM_MILLIS] stops. */
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
            state == null -> when {
                // Mic not allowed yet: the app's Record asks for it.
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                    startActivityAndCollapse(activity(MainActivity::class.java))
                // Locked: the phone is unlocked first, so nobody records on a phone that isn't theirs.
                isLocked -> unlockAndRun { record() }
                else -> record()
            }
            state.finishing -> Unit
            !Recording.settings.tileStopTwice || armed?.isActive == true -> { armed?.cancel(); armed = null; RecordingService.stop(this) }
            else -> {
                armed = scope?.launch { delay(ARM_MILLIS); armed = null; show(Recording.session.state.value) }
                show(state)
            }
        }
    }

    /**
     * Records in the background, the shade left open: while System UI is bound to the tile, the app shares its
     * microphone access. If Android refuses anyway, [RecordFromTileActivity] starts it from the foreground.
     */
    private fun record() {
        clicks.launch {
            val next = withContext(Dispatchers.IO) { nextFromRecordBar() }
            if (Recording.session.isRecording) return@launch
            runCatching { next.start(this@RecordTileService, fromTile = true) }.onFailure {
                android.util.Log.w("RecordTileService", "recording from the tile refused, through an activity", it)
                startActivityAndCollapse(activity(RecordFromTileActivity::class.java))
            }
        }
    }

    private fun activity(c: Class<*>) = PendingIntent.getActivity(
        this, c.name.hashCode(), Intent(this, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
        clicks.cancel()
        super.onDestroy()
    }

    private companion object {
        const val ARM_MILLIS = 4_000L
    }
}

/** The moment the Record bar would start now: its guessed labels and language, its Live, After or Only record. */
class NextMoment(val labels: List<String>, val labelNames: String, val language: String, val mode: RecordMode) {
    fun start(context: android.content.Context, fromTile: Boolean = false) = RecordingService.start(
        context, labels, labelNames, language, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()), mode, fromTile,
    )
}

/** [NextMoment] from the moments on disk and the settings. Files: not on the main thread. */
fun nextFromRecordBar(): NextMoment {
    // A fresh process (the app not opened since): the moments aren't read yet, and the guess needs them.
    if (Recording.library.library.value == Library()) Recording.library.reload()
    val library = Recording.library.library.value
    val next = notebooksState(library, System.currentTimeMillis(), Recording.utcOffset)
    val labels = next.guess?.labels.orEmpty().filter { id -> library.labels.any { it.id == id } }
    return NextMoment(labels, labelNames(labels, library.labels), next.language, Recording.settings.recordMode)
}

/**
 * The tile's fallback when Android won't let the microphone start from the background: starts [NextMoment] from the
 * foreground, shows nothing and finishes.
 */
class RecordFromTileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Recording.session.isRecording) { finish(); return }
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) { nextFromRecordBar() }
            if (!Recording.session.isRecording) next.start(this@RecordFromTileActivity)
            finish()
        }
    }
}
