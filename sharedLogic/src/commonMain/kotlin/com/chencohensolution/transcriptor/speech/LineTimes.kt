package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment

/**
 * Where the lines of a recording are, without their text: [audio] (16 kHz mono, read from the start in pieces, so
 * memory stays small) split at pauses as live transcription splits it ([Utterances], [limits]). Each line has its
 * start and end and an empty text, ready for [RefinePass] to write. This is the first half of transcribing a moment
 * that was only recorded ([Lecture.pendingTranscript]). [progress] gets the milliseconds read so far; [stop] true ends
 * it early with what it has.
 */
fun lineTimes(
    audio: AudioSource,
    limits: UtteranceLimits = UtteranceLimits.lines,
    progress: (atMillis: Long) -> Unit = {},
    stop: () -> Boolean = { false },
): List<Segment> {
    val utterances = Utterances(limits)
    val lines = ArrayList<Segment>()
    fun add(u: Utterance) {
        lines += Segment(id = "s${lines.size + 1}", startMillis = u.startSample / 16, endMillis = u.endSample / 16, text = "", modelId = "")
    }
    var read = 0L
    while (!stop()) {
        val samples = audio.read(LINE_TIMES_CHUNK) ?: break
        read += samples.size
        utterances.feed(samples).forEach(::add)
        progress(read / 16)
    }
    utterances.close()?.let(::add)
    return lines
}

/** How much [lineTimes] reads at a time: 30 s. */
private const val LINE_TIMES_CHUNK = 30 * 16_000

/**
 * A moment that was only recorded, transcribed: [lines] ([lineTimes]) written by [write] (a [RefinePass] run over a
 * moment holding them), without the lines the model found no words in (noise, music). Its notes, labels and title are
 * [lecture]'s; it no longer waits ([Lecture.pendingTranscript]).
 */
fun transcribed(lecture: Lecture, lines: List<Segment>, modelId: String, write: (Lecture) -> Lecture): Lecture {
    val written = write(lecture.copy(segments = lines)).segments.filter { it.text.isNotBlank() }
    return lecture.copy(
        segments = written.mapIndexed { i, s -> s.copy(id = "s${i + 1}", modelId = modelId) },
        refinedWith = modelId, pendingTranscript = false,
    )
}
