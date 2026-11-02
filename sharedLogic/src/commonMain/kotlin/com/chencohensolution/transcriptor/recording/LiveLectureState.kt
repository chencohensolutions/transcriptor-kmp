package com.chencohensolution.transcriptor.recording

import com.chencohensolution.transcriptor.notebook.Lecture

/** The lecture screen while recording, as [LectureSession] produces it. */
data class LiveLectureState(
    val notebookTitle: String,
    val lecture: Lecture,
    /** The line live transcription is writing now, before its end; null in a pause between lines, or without it. */
    val partial: String?,
    /** The position in the audio: what a note typed now is pinned to. */
    val elapsedMillis: Long,
    /** The microphone's level, 0..1, for the meter. */
    val level: Float,
    val paused: Boolean,
    /** The live model; null until live transcription is built. */
    val liveModelName: String?,
    /** Live transcription is this far behind the audio (it catches up in pauses); null without it. */
    val lagMillis: Long?,
)
