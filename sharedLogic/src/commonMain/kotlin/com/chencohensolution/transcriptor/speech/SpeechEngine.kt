package com.chencohensolution.transcriptor.speech

/** A piece of text the model heard, with its times from the start of the samples it was given. */
data class Piece(val startMillis: Long, val endMillis: Long, val text: String)

/**
 * A loaded speech model (whisper.cpp on the phone; a fake in tests). Not thread-safe: one call at a time, on one
 * thread.
 */
interface SpeechEngine : AutoCloseable {
    /** [samples]: 16 kHz mono, -1..1. [prompt]: the text just before, to keep names and spelling consistent. */
    fun transcribe(samples: FloatArray, language: String, prompt: String?): List<Piece>
}
