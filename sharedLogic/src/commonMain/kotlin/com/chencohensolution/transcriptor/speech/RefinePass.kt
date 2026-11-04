package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Lecture

/**
 * The refine pass after the lecture: every line transcribed again, from its own audio, with a model the user picks
 * (usually Whisper large-v3: more accurate, with punctuation, slower than real time on a phone). Lines keep their times and speakers;
 * lines the user edited keep their text too. A line the model gives nothing for (noise it won't invent from) keeps the
 * live text. The word times of the live model no longer match the new text, so they are dropped: run the speaker pass
 * first.
 */
class RefinePass(
    /** A line's text from its samples (16 kHz mono): `transcribeLine` for Whisper, the streaming model's for others. */
    private val transcribe: (FloatArray) -> String,
    /** A stretch of the recording, 16 kHz mono. */
    private val audio: (startMillis: Long, endMillis: Long) -> FloatArray,
) {
    /** With a Whisper model, and live transcription's filters. */
    constructor(engine: SpeechEngine, language: String, audio: (Long, Long) -> FloatArray) :
        this({ samples -> transcribeLine(engine, samples, language) }, audio)

    fun run(lecture: Lecture, modelId: String, progress: (Float) -> Unit = {}): Lecture {
        val lines = lecture.segments
        val refined = lines.mapIndexed { i, line ->
            val done = if (line.edited) line else {
                // A little before and after the line, so its first and last words aren't cut; at most Whisper's 30 s.
                val start = (line.startMillis - LEAD_MILLIS).coerceAtLeast(0)
                val end = minOf(line.endMillis + TAIL_MILLIS, start + MAX_MILLIS, lecture.durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE)
                val text = transcribe(audio(start, end))
                if (text.isEmpty()) line else line.copy(text = text, modelId = modelId, words = emptyList())
            }
            progress((i + 1f) / lines.size)
            done
        }
        return lecture.copy(segments = refined, refinedWith = modelId, speakerPass = lecture.speakerPass)
    }

    companion object {
        const val LEAD_MILLIS = 200L
        const val TAIL_MILLIS = 300L
        const val MAX_MILLIS = 29_500L
    }
}

/** A line's text from a streaming model: the line's audio, then quiet to flush its last words. */
fun transcribeLineStreaming(engine: StreamingEngine, samples: FloatArray): String {
    engine.reset()
    engine.accept(samples)
    engine.flush()
    return cleanLine(engine.text).also { engine.reset() }
}
