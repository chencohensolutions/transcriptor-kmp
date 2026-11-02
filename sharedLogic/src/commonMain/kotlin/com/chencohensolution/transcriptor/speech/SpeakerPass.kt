package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.Word

/** A speaker's turn: [speaker] spoke from [startMillis] to [endMillis]. Turns of different speakers may overlap. */
data class Turn(val startMillis: Long, val endMillis: Long, val speaker: String)

/**
 * Who speaks when in a stretch of audio (pyannote segmentation 3.0 with voice prints and clustering, on sherpa-onnx on
 * the phone; a fake in tests): times from the start of [samples], speakers numbered within it.
 */
fun interface Diarizer {
    fun diarize(samples: FloatArray): List<Turn>
}

/** The recording as the models take it, 16 kHz mono, read from the start in pieces; null at the end. */
fun interface AudioSource {
    fun read(maxSamples: Int): FloatArray?
}

/**
 * The speaker pass after the lecture: who spoke when in the whole recording, with pyannote's segmentation (overlaps
 * included), which live labels can't see, and the lines relabeled and split to match. The recording is read in
 * [CHUNK_SAMPLES] pieces so memory stays small; each piece is diarized, and its speakers are matched to the others' and
 * to the voices the notebook knows by voice print (a [SpeakerTracker]), so a named voice keeps its name.
 */
class SpeakerPass(private val diarizer: Diarizer, private val embedder: VoiceEmbedder) {

    class Result(val turns: List<Turn>, val speakers: List<Speaker>, val tracker: SpeakerTracker)

    fun run(audio: AudioSource, known: List<Voice>, progress: (millis: Long) -> Unit = {}): Result {
        val tracker = SpeakerTracker(known)
        val turns = mutableListOf<Turn>()
        var offset = 0L
        while (true) {
            val chunk = audio.read(CHUNK_SAMPLES) ?: break
            val local = diarizer.diarize(chunk)
            // Each of the piece's speakers, by a print of its longest turns (up to PRINT_SAMPLES of them).
            val ids = local.groupBy { it.speaker }.mapValues { (_, own) ->
                val audioOf = FloatBuilder()
                for (t in own.sortedByDescending { it.endMillis - it.startMillis }) {
                    if (audioOf.size >= PRINT_SAMPLES) break
                    val a = (t.startMillis * 16).toInt().coerceIn(0, chunk.size)
                    val b = (t.endMillis * 16).toInt().coerceIn(a, chunk.size)
                    audioOf.add(chunk.copyOfRange(a, b))
                }
                if (audioOf.size < SpeakerLabeler.MIN_SAMPLES) null else tracker.assign(embedder.embed(audioOf.toArray()))
            }
            for (t in local) {
                val id = ids[t.speaker] ?: continue
                turns += Turn(offset / 16 + t.startMillis, offset / 16 + t.endMillis, id)
            }
            offset += chunk.size
            progress(offset / 16)
        }
        return Result(turns.sortedBy { it.startMillis }, tracker.speakers, tracker)
    }

    companion object {
        /** Five minutes: about 19 MB of samples at a time. */
        const val CHUNK_SAMPLES = 5 * 60 * 16_000

        /** Up to 20 s of a speaker's speech for their print. */
        const val PRINT_SAMPLES = 20 * 16_000
    }
}

/**
 * The lecture with its lines following the speakers' [turns]: a line with word times is split where the speaker
 * changes (a single word of another speaker stays where it is), a line without them gets the speaker who spoke most
 * of it. Lines the user edited keep their text and get the main speaker. Lines are numbered again.
 */
fun Lecture.withTurns(turns: List<Turn>, speakers: List<Speaker>): Lecture {
    fun overlap(t: Turn, a: Long, b: Long) = (minOf(t.endMillis, b) - maxOf(t.startMillis, a)).coerceAtLeast(0)
    fun mainSpeaker(a: Long, b: Long) =
        turns.groupBy { it.speaker }.mapValues { (_, own) -> own.sumOf { overlap(it, a, b) } }
            .filterValues { it > 0 }.maxByOrNull { it.value }?.key

    val lines = segments.flatMap { line ->
        if (line.words.isEmpty() || line.edited) return@flatMap listOf(line.copy(speakerId = mainSpeaker(line.startMillis, line.endMillis) ?: line.speakerId))
        // Each word's speaker: the turn it falls in (the one already speaking, in an overlap), else the one before.
        var previous: String? = null
        val said = line.words.map { w ->
            val here = turns.filter { w.atMillis in it.startMillis..it.endMillis }.map { it.speaker }
            val s = if (previous in here) previous else here.firstOrNull() ?: previous
            previous = s
            w to s
        }
        val firstKnown = said.firstOrNull { it.second != null }?.second
        val runs = mutableListOf<MutableList<Pair<Word, String?>>>()
        for ((w, s) in said.map { (w, s) -> w to (s ?: firstKnown) }) {
            if (runs.isNotEmpty() && runs.last().last().second == s) runs.last() += w to s else runs += mutableListOf(w to s)
        }
        // A one-word run is too little to be another speaker: it joins the run before it.
        var i = 1
        while (i < runs.size) {
            if (runs[i].size == 1 && runs.size > 1) {
                runs[i - 1] += runs[i].map { (w, _) -> w to runs[i - 1].last().second }
                runs.removeAt(i)
                if (i < runs.size && runs[i].first().second == runs[i - 1].last().second) { runs[i - 1] += runs[i]; runs.removeAt(i) }
            } else i++
        }
        if (runs.size > 1 && runs[0].size == 1) { runs[1].addAll(0, runs[0].map { (w, _) -> w to runs[1].first().second }); runs.removeAt(0) }
        runs.mapIndexed { k, run ->
            Segment(
                id = "", startMillis = if (k == 0) line.startMillis else run.first().first.atMillis,
                endMillis = if (k == runs.lastIndex) line.endMillis else runs[k + 1].first().first.atMillis,
                text = if (runs.size == 1) line.text else run.joinToString(" ") { it.first.text },
                modelId = line.modelId, speakerId = run.first().second ?: line.speakerId, words = run.map { it.first },
            )
        }
    }.mapIndexed { i, s -> s.copy(id = "s${i + 1}") }
    val used = lines.mapNotNull { it.speakerId }.toSet()
    return copy(segments = lines, speakers = speakers.filter { it.id in used }, speakerPass = true)
}
