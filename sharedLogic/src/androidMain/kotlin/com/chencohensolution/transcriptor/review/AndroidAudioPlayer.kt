package com.chencohensolution.transcriptor.review

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.Path

/**
 * The recording on Android's [MediaPlayer] (WAV and FLAC both): seeks land on the exact millisecond, so a line starts
 * on its first word; the position is read every 100 ms while playing. [open] opens a recording's file, in the app's
 * storage or in the lectures folder outside it.
 */
class AndroidAudioPlayer(
    private val open: (Path) -> ParcelFileDescriptor = { ParcelFileDescriptor.open(java.io.File(it.toString()), ParcelFileDescriptor.MODE_READ_ONLY) },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AudioPlayer {
    private val position = MutableStateFlow(0L)
    private val running = MutableStateFlow(false)
    override val positionMillis: StateFlow<Long> = position
    override val playing: StateFlow<Boolean> = running

    private var player: MediaPlayer? = null
    private var ticking: Job? = null
    private var speed = 1f

    override fun load(file: Path) {
        release()
        player = MediaPlayer().apply {
            // The player keeps its own copy of the descriptor.
            open(file).use { setDataSource(it.fileDescriptor) }
            prepare()
            setOnCompletionListener { running.value = false; stopTicking(); position.value = duration.toLong() }
        }
        position.value = 0
        speed = 1f
    }

    override fun play() {
        val p = player ?: return
        p.playbackParams = PlaybackParams().setSpeed(speed)
        p.start()
        running.value = true
        ticking?.cancel()
        ticking = scope.launch {
            while (isActive) {
                player?.let { position.value = it.currentPosition.toLong() }
                delay(100)
            }
        }
    }

    override fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        running.value = false
        stopTicking()
        player?.let { position.value = it.currentPosition.toLong() }
    }

    override fun seekTo(millis: Long) {
        player?.seekTo(millis, MediaPlayer.SEEK_CLOSEST)
        position.value = millis
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        // Setting the speed of a paused player starts it: only while playing.
        player?.takeIf { it.isPlaying }?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
    }

    override fun release() {
        stopTicking()
        player?.release()
        player = null
        running.value = false
    }

    private fun stopTicking() {
        ticking?.cancel()
        ticking = null
    }
}
