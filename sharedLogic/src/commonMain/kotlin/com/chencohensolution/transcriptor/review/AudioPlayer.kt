package com.chencohensolution.transcriptor.review

import kotlinx.coroutines.flow.StateFlow
import okio.Path

/** A lecture's recording, played back. One per platform (Android's in androidMain); tests use a fake. */
interface AudioPlayer {
    val positionMillis: StateFlow<Long>
    val playing: StateFlow<Boolean>

    fun load(file: Path)
    fun play()
    fun pause()
    fun seekTo(millis: Long)
    fun setSpeed(speed: Float)
    fun release()
}
