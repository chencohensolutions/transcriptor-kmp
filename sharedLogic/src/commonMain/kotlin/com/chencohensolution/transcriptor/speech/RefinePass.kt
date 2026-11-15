package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment

/**
 * The refine pass after the lecture: the lines transcribed again, from their audio, with a model the user picks
 * (usually Whisper large-v3: more accurate, with punctuation, slower than real time on a phone). Lines keep their times and speakers;
 * lines the user edited keep their text too. A line the model gives nothing for (noise it won't invent from) keeps the
 * live text. The word times of the live model no longer match the new text, so they are dropped: run the speaker pass
 * first.
 *
 * With an engine that times words ([words]), consecutive lines go to the model together, up to [WINDOW_MILLIS] of
 * audio, and each word goes back to the line it was said in: the model hears each line with what was said around
 * it, and the words at a line's edges aren't cut. Measured on a Hebrew lecture against Whisper on the whole file, 10%
 * of the words differed this way and 13% a line at a time (18% live). Other engines improve a line at a time.
 */
class RefinePass(
    /** A line's text from its samples (16 kHz mono): `transcribeLine` for Whisper, the streaming model's for others. */
    private val transcribe: (FloatArray) -> String,
    /** A stretch of the recording, 16 kHz mono. */
    private val audio: (startMillis: Long, endMillis: Long) -> FloatArray,
    /** A stretch's words with their times from its start, already filtered; null when the engine can't time them. */
    private val words: ((FloatArray) -> List<Piece>?)? = null,
) {
    /** With a Whisper model, and live transcription's filters. */
    constructor(engine: SpeechEngine, language: String, audio: (Long, Long) -> FloatArray) :
        this({ samples -> transcribeLine(engine, samples, language) }, audio, if (engine.timesWords) { samples -> timedWords(engine, samples, language) } else null)

    /**
     * Improves [lecture]'s lines with [modelId]. [stop] is asked before each line or window: true ends the pass at once
     * with [Stopped] (a recording started and needs the memory its model holds), nothing written.
     */
    fun run(lecture: Lecture, modelId: String, progress: (Float) -> Unit = {}, stop: () -> Boolean = { false }): Lecture {
        val lines = lecture.segments
        val endOfAudio = lecture.durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE
        val refined = ArrayList<Segment>(lines.size)
        fun done(line: Segment) {
            refined += line
            progress(refined.size.toFloat() / lines.size)
        }
        fun oneLine(line: Segment) {
            // A little before and after the line, so its first and last words aren't cut; at most Whisper's 30 s.
            val start = (line.startMillis - LEAD_MILLIS).coerceAtLeast(0)
            val end = minOf(line.endMillis + TAIL_MILLIS, start + MAX_MILLIS, endOfAudio)
            done(refine(line, transcribe(audio(start, end)), modelId))
        }
        var i = 0
        while (i < lines.size) {
            if (stop()) throw Stopped()
            val line = lines[i]
            if (line.edited) { done(line); i++; continue }
            if (words == null) { oneLine(line); i++; continue }
            // The window: this line and the unedited ones after it, while they fit.
            val start = (line.startMillis - LEAD_MILLIS).coerceAtLeast(0)
            var last = i
            while (last + 1 < lines.size && !lines[last + 1].edited && lines[last + 1].endMillis + TAIL_MILLIS - start <= WINDOW_MILLIS) last++
            val window = lines.subList(i, last + 1)
            val end = minOf(window.last().endMillis + TAIL_MILLIS, start + MAX_MILLIS, endOfAudio)
            val heard = words.invoke(audio(start, end))
            if (heard == null) window.forEach(::oneLine)
            else assign(window, heard, start).forEachIndexed { k, text -> done(refine(window[k], text, modelId)) }
            i = last + 1
        }
        return lecture.copy(segments = refined, refinedWith = modelId, speakerPass = lecture.speakerPass)
    }

    private fun refine(line: Segment, text: String, modelId: String) =
        if (text.isEmpty()) line else line.copy(text = text, modelId = modelId, words = emptyList())

    /** The pass was stopped before its end ([run]'s stop). */
    class Stopped : Exception("Improving stopped: a recording started")

    companion object {
        const val LEAD_MILLIS = 200L
        const val TAIL_MILLIS = 300L
        const val MAX_MILLIS = 29_500L
        /** The longest stretch several lines are improved in together: Whisper's 30 s, with room to spare. */
        const val WINDOW_MILLIS = 28_000L

        /**
         * Each word to the line it was said in (by its middle, from [offset] in the recording), else the nearest:
         * the lines' new texts, empty for a line that got no word.
         */
        fun assign(lines: List<Segment>, words: List<Piece>, offset: Long): List<String> {
            val texts = List(lines.size) { StringBuilder() }
            for (word in words) {
                val text = word.text.trim()
                if (text.isEmpty()) continue
                val at = offset + (word.startMillis + word.endMillis) / 2
                val k = lines.indices.minBy { lines[it].distanceTo(at) }
                texts[k].append(' ').append(text)
            }
            return texts.map { cleanLine(it.toString()) }
        }

        private fun Segment.distanceTo(at: Long) = when {
            at < startMillis -> startMillis - at
            at > endMillis -> at - endMillis
            else -> 0
        }

        /**
         * Whisper's timed words for a stretch, with live transcription's filters: none where it heard no speech, none
         * when it wrote far more words than anyone says in that time (a model stuck in a loop). Null when [engine]
         * can't time words.
         */
        fun timedWords(engine: SpeechEngine, samples: FloatArray, language: String): List<Piece>? {
            val words = engine.transcribeWords(samples, language)?.filter { it.noSpeech < UtteranceTranscriber.NO_SPEECH } ?: return null
            val count = words.count { word -> word.text.any(Char::isLetterOrDigit) }
            return if (count > 2 + samples.size / 16_000.0 * UtteranceTranscriber.MAX_WORDS_PER_SECOND) emptyList() else words
        }
    }
}

/** A line's text from a streaming model: the line's audio, then quiet to flush its last words. */
fun transcribeLineStreaming(engine: StreamingEngine, samples: FloatArray): String {
    engine.reset()
    engine.accept(samples)
    engine.flush()
    return cleanLine(engine.text).also { engine.reset() }
}
