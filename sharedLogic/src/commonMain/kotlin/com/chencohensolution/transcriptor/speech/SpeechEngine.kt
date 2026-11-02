package com.chencohensolution.transcriptor.speech

/**
 * A piece of text the model heard, with its times from the start of the samples it was given, and the model's own
 * estimate that there was no speech in it (0..1).
 */
data class Piece(val startMillis: Long, val endMillis: Long, val text: String, val noSpeech: Float = 0f)

/**
 * A loaded speech model (whisper.cpp on the phone; a fake in tests). Not thread-safe: one call at a time, on one
 * thread.
 */
interface SpeechEngine : AutoCloseable {
    /** [samples]: 16 kHz mono, -1..1. [prompt]: text to condition on (names, spelling); null for none. */
    fun transcribe(samples: FloatArray, language: String, prompt: String?): List<Piece>
}
