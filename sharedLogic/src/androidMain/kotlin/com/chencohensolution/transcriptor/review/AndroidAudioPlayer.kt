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
    // A streamed recording is prepared in the background: play and seek wait for it.
    private var prepared = true
    private var playWhenPrepared = false
    private var seekWhenPrepared: Long? = null

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
        prepared = true
    }

    override fun load(stream: AudioStream) {
        release()
        prepared = false
        playWhenPrepared = false
        seekWhenPrepared = null
        position.value = 0
        speed = 1f
        player = MediaPlayer().apply {
            setDataSource(StreamedSource(stream))
            setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                prepared = true
                seekWhenPrepared?.let { p.seekTo(it, MediaPlayer.SEEK_CLOSEST) }
                seekWhenPrepared = null
                if (playWhenPrepared) play()
            }
            setOnCompletionListener { running.value = false; stopTicking(); position.value = duration.toLong() }
            setOnErrorListener { _, what, extra ->
                android.util.Log.w("AudioPlayer", "streaming failed: $what/$extra")
                running.value = false
                stopTicking()
                true
            }
            // Reads the first blocks from the server: off the main thread, as the rest.
            prepareAsync()
        }
    }

    override fun play() {
        val p = player ?: return
        if (!prepared) {
            playWhenPrepared = true
            running.value = true
            return
        }
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
        playWhenPrepared = false
        if (!prepared) {
            running.value = false
            return
        }
        player?.takeIf { it.isPlaying }?.pause()
        running.value = false
        stopTicking()
        player?.let { position.value = it.currentPosition.toLong() }
    }

    override fun seekTo(millis: Long) {
        if (!prepared) seekWhenPrepared = millis else player?.seekTo(millis, MediaPlayer.SEEK_CLOSEST)
        position.value = millis
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        // Setting the speed of a paused player starts it: only while playing.
        if (prepared) player?.takeIf { it.isPlaying }?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
    }

    override fun release() {
        stopTicking()
        playWhenPrepared = false
        player?.release()
        player = null
        running.value = false
    }

    private fun stopTicking() {
        ticking?.cancel()
        ticking = null
    }
}

/**
 * A recording on the server as Android's player reads it: each read goes to shed's reader (from its cache, else the
 * server, a block at a time). The player reads on its own threads, so waiting here holds no screen.
 */
private class StreamedSource(private val stream: AudioStream) : android.media.MediaDataSource() {
    private var size: Long? = null

    override fun getSize(): Long = size ?: try {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { stream.size() }.also { size = it }
    } catch (e: Exception) {
        -1
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val total = getSize()
        if (total in 0..position) return -1
        if (length == 0) return 0
        val bytes = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { stream.read(position, length) }
        } catch (e: Exception) {
            throw java.io.IOException("reading the recording from the server failed", e)
        }
        if (bytes.isEmpty()) return -1
        bytes.copyInto(buffer, offset, 0, minOf(bytes.size, length))
        return minOf(bytes.size, length)
    }

    override fun close() {}
}
