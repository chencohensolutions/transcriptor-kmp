package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Segment

/**
 * "Tune on this lecture": what the voice-print model's "fewer ↔ more speakers" setting should be, judged by the
 * user's own labels in a lecture recorded with live speaker turns.
 *
 * - [lines] is the lecture's lines as the user left them; a line counts when it has a speaker and overlaps a stretch.
 * - [stretches] is what [LiveSpeakers] decided while recording, kept with the lecture.
 * - [tuned] is the model's tuned live threshold, and [now] is the user's setting (-1..1).
 *
 * The stretches are replayed ([replayStretches]) at thresholds across the setting's range. At each one, every line
 * gets the speaker of the stretches overlapping it most, and the replayed speakers are matched one to one with the
 * user's. The setting with the most lines matching wins; a tie goes to the one nearest [now].
 */
object SpeakerTuning {
    data class Result(
        /** Lines the user gave another speaker than live did (relabeled, or merged into another speaker). */
        val corrections: Int,
        /** The lines judged. */
        val lines: Int,
        /** Lines matching at the user's setting now, and at the best one. */
        val matchesNow: Int,
        val matches: Int,
        /** Speakers at the user's setting now, and at the best one. */
        val speakersNow: Int,
        val speakers: Int,
        /** The best setting, -1 (fewer speakers) to 1 (more). */
        val more: Float,
    )

    /** The speaker heard most in [line], by [stretches] with [speakers] (one per stretch); null when none overlaps. */
    fun speakerOf(line: Segment, stretches: List<LiveStretch>, speakers: List<String?>): String? {
        val heard = mutableMapOf<String, Long>()
        stretches.forEachIndexed { i, s ->
            val who = speakers[i] ?: return@forEachIndexed
            val overlap = minOf(line.endMillis, s.endMillis) - maxOf(line.startMillis, s.startMillis)
            if (overlap > 0) heard[who] = (heard[who] ?: 0) + overlap
        }
        return heard.maxByOrNull { it.value }?.key
    }

    /** Lines whose speaker the user changed from what live speaker turns gave them. */
    fun corrections(lines: List<Segment>, stretches: List<LiveStretch>): Int {
        val live = stretches.map { it.speaker }
        return lines.count { l -> l.speakerId != null && speakerOf(l, stretches, live).let { it != null && it != l.speakerId } }
    }

    fun tune(lines: List<Segment>, stretches: List<LiveStretch>, tuned: Float, now: Float): Result? {
        val judged = lines.filter { it.speakerId != null && speakerOf(it, stretches, stretches.map { s -> s.speaker }) != null }
        if (judged.isEmpty() || stretches.none { it.print != null }) return null
        fun score(more: Float): Pair<Int, Int> {
            val threshold = VoicePrintThresholds.shift(tuned, more)
            val replayed = replayStretches(stretches, threshold)
            val predicted = judged.map { speakerOf(it, stretches, replayed) }
            return matching(predicted, judged.map { it.speakerId!! }) to replayed.filterNotNull().toSet().size
        }
        val steps = (-20..20).map { it / 20f }
        val scored = steps.associateWith { score(it) }
        val best = scored.entries.maxWith(compareBy<Map.Entry<Float, Pair<Int, Int>>> { it.value.first }.thenBy { -kotlin.math.abs(it.key - now) })
        val atNow = score(now)
        return Result(
            corrections = corrections(lines, stretches), lines = judged.size,
            matchesNow = atNow.first, matches = best.value.first,
            speakersNow = atNow.second, speakers = best.value.second, more = best.key,
        )
    }

    /** How many positions agree when [predicted] speakers are renamed one to one to [truth]'s, at best (greedy). */
    fun matching(predicted: List<String?>, truth: List<String>): Int {
        val counts = mutableMapOf<Pair<String, String>, Int>()
        predicted.zip(truth).forEach { (p, t) -> if (p != null) counts[p to t] = (counts[p to t] ?: 0) + 1 }
        val usedP = mutableSetOf<String>()
        val usedT = mutableSetOf<String>()
        var total = 0
        for ((pair, n) in counts.entries.sortedByDescending { it.value }) {
            if (pair.first in usedP || pair.second in usedT) continue
            usedP += pair.first
            usedT += pair.second
            total += n
        }
        return total
    }
}
