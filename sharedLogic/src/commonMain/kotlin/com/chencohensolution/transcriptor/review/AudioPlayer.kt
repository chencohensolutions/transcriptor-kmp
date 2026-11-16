package com.chencohensolution.transcriptor.review

import kotlinx.coroutines.flow.StateFlow
import okio.Path

/** A lecture's recording, played back. One per platform (Android's in androidMain); tests use a fake. */
interface AudioPlayer {
    val positionMillis: StateFlow<Long>
    val playing: StateFlow<Boolean>

    fun load(file: Path)

    /** Plays [stream] (a recording kept on the server), reading only what is played: no download first. */
    fun load(stream: AudioStream)
    fun play()
    fun pause()
    fun seekTo(millis: Long)
    fun setSpeed(speed: Float)
    fun release()
}

/**
 * A recording read by ranges as it plays (shed's reader of a file on the server): [size] and [read] may go to the
 * network, so the player calls them off the main thread.
 */
class AudioStream(
    val size: suspend () -> Long,
    val read: suspend (position: Long, length: Int) -> ByteArray,
)
