package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import kotlinx.coroutines.flow.StateFlow

/** What live transcription has so far. */
data class LiveOutput(
    val segments: List<Segment> = emptyList(),
    /** The line being spoken now, transcribed so far; null between lines. */
    val partial: String? = null,
    /** How far the text is behind the audio; 0 when it has caught up. */
    val lagMillis: Long = 0,
    /** The model is still loading. */
    val loading: Boolean = true,
    /** The model failed to load or run. */
    val error: String? = null,
    /** The speakers told apart so far ([SpeakerLabeler]); the lines' speakerId points into it. */
    val speakers: List<Speaker> = emptyList(),
)

/**
 * Live transcription of a lecture: the recording's PCM in, lines ([Segment]s, with their start and end in the
 * recording) and the line being spoken out. [UtteranceTranscriber] runs a model a line at a time (Whisper);
 * [StreamingTranscriber] a streaming model (Zipformer).
 */
interface LiveTranscriber {
    val output: StateFlow<LiveOutput>

    /** The recording's PCM (48 kHz stereo 16 bit), from the recorder's thread. Pauses don't reach it. */
    fun feed(pcm: ByteArray, size: Int)

    /** Ends the line being spoken (Pause). */
    fun pause()

    /** Ends the line being spoken, transcribes everything still waiting, and stops. */
    suspend fun finish()
}

/**
 * A line as the model wrote it, tidied: no punctuation or spaces before the first word, and a word or phrase (up to
 * eight words) said three or more times in a row (a model stuck in a loop: "אתם, אתם, אתם") kept once.
 */
fun cleanLine(text: String): String {
    var t = text.trim().trimStart { !it.isLetterOrDigit() }.replace(Regex("""\s+"""), " ")
    t = Regex("""((?<!\S)[^\s,.]+(?:\s+[^\s,.]+){0,7}[,.]?)(?:\s+\1){2,}""").replace(t) { it.groupValues[1] }
    return t.trim()
}
