package com.chencohensolution.transcriptor.speech

import com.chencohensolution.transcriptor.notebook.Speaker
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.sqrt

/** A loaded voice-print model (3D-Speaker CAM++ on sherpa-onnx on the phone; a fake in tests). One thread. */
interface VoiceEmbedder : AutoCloseable {
    /** The voice print of [samples] (16 kHz mono): close prints, same speaker. */
    fun embed(samples: FloatArray): FloatArray
}

/**
 * A person whose voice the notebook knows, from a lecture where the user named them: their [print] by voice-print
 * [model] (a model's id; empty: the one in use). Prints of different models never compare.
 */
data class Voice(val name: String, val print: List<Float>, val model: String = "")

/**
 * Tells speakers apart during a lecture, a line at a time: each line's voice print joins the speaker whose average
 * print is closest, if it is at least [threshold] alike (cosine), else starts a new speaker ("speaker-N", named by
 * the user later). Voices the notebook knows come first, under their names, but join the lecture's speakers only
 * once they speak. Thread-safe: lines come from the transcription worker, renames from the UI.
 */
class SpeakerTracker(
    known: List<Voice> = emptyList(),
    private val threshold: Float = THRESHOLD,
    /** The voice-print model whose prints it compares (its id); known voices of another model are left out. */
    val model: String = "",
) {
    private class Cluster(val voiceName: String?, var sum: FloatArray, var lines: Int, var id: String? = null)

    private val lock = SynchronizedObject()
    private val clusters = known.filter { model.isEmpty() || it.model.isEmpty() || it.model == model }.map { Cluster(it.name, normalized(it.print.toFloatArray()), 1) }.toMutableList()
    private var next = 1

    /** The lecture's speakers, in the order they first spoke; known voices with their names, others unnamed. */
    val speakers: List<Speaker>
        get() = synchronized(lock) {
            clusters.filter { it.id != null }.sortedBy { it.id!!.removePrefix("speaker-").toInt() }
                .map { Speaker(it.id!!, it.voiceName ?: "") }
        }

    /** The speaker of a line with voice print [print]: an id from [speakers]. */
    fun assign(print: FloatArray): String = synchronized(lock) {
        val p = normalized(print)
        val best = clusters.maxByOrNull { cosine(it.sum, p) }
        val cluster = if (best != null && cosine(best.sum, p) >= threshold) {
            best.sum = FloatArray(p.size) { best.sum[it] + p[it] }
            best.lines++
            best
        } else {
            Cluster(null, p, 1).also(clusters::add)
        }
        cluster.id ?: "speaker-${next++}".also { cluster.id = it }
    }

    /**
     * Groups voice prints of short windows of one line by speaker, without changing the speakers: each window joins
     * the closest speaker so far or of this line (at least [threshold] alike), or starts a new group. Equal numbers,
     * same voice.
     */
    fun group(prints: List<FloatArray>): List<Int> = synchronized(lock) {
        val centers = clusters.map { normalized(it.sum) }.toMutableList()
        val sums = centers.map { it.copyOf() }.toMutableList()
        prints.map { print ->
            val p = normalized(print)
            val best = centers.indices.maxByOrNull { cosine(centers[it], p) }
            if (best != null && cosine(centers[best], p) >= threshold) {
                if (best >= clusters.size) {
                    sums[best] = FloatArray(p.size) { sums[best][it] + p[it] }
                    centers[best] = normalized(sums[best])
                }
                best
            } else {
                centers.add(p)
                sums.add(p.copyOf())
                centers.size - 1
            }
        }
    }

    /** The average voice print of speaker [id], to remember them in the notebook; null if they never spoke. */
    fun print(id: String): List<Float>? = synchronized(lock) { clusters.firstOrNull { it.id == id }?.let { normalized(it.sum).toList() } }

    companion object {
        /**
         * Cosine similarity at which two CAM++ prints count as one speaker (same-speaker pairs are mostly above). Other
         * models have their own ([VoicePrintThresholds]).
         */
        const val THRESHOLD = 0.5f

        private fun normalized(v: FloatArray): FloatArray {
            val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat().takeIf { it > 0 } ?: 1f
            return FloatArray(v.size) { v[it] / n }
        }

        private fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return -1f
            var dot = 0.0
            var na = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i] }
            return (dot / sqrt(na).coerceAtLeast(1e-9)).toFloat() // b is normalized
        }
    }
}

/**
 * Labels lines with their speaker as live transcription closes them, on its worker: the voice-print model loads on
 * the first line. A line shorter than [MIN_SAMPLES] gets no label (too little voice to tell); a failure only loses
 * the label.
 */
class SpeakerLabeler(val tracker: SpeakerTracker, private val load: () -> VoiceEmbedder) {
    private var embedder: VoiceEmbedder? = null
    private var failed = false

    fun label(samples: FloatArray): String? {
        if (samples.size < MIN_SAMPLES || failed) return null
        return try {
            val e = embedder ?: load().also { embedder = it }
            tracker.assign(e.embed(samples))
        } catch (e: Throwable) {
            failed = embedder == null
            null
        }
    }

    /**
     * Who spoke which part of a line's [samples]: voice prints of [WINDOW]-long windows every [HOP] are grouped by
     * speaker ([SpeakerTracker.group]); a change that lasts at least two windows splits the line, at the [cuts]
     * (sample offsets where a word starts) nearest to it. Each part is then labeled as a line of its own. One part, the
     * whole line, when the speaker doesn't change or the line is too short to tell.
     */
    fun split(samples: FloatArray, cuts: List<Int>): List<SpeakerPart> {
        val whole = listOf(SpeakerPart(0, samples.size, null))
        if (samples.size < 2 * WINDOW || failed || cuts.isEmpty()) return whole.map { it.copy(speaker = label(samples)) }
        val groups = try {
            val e = embedder ?: load().also { embedder = it }
            val starts = (0..samples.size - WINDOW step HOP).toList()
            tracker.group(starts.map { e.embed(samples.copyOfRange(it, it + WINDOW)) })
        } catch (e: Throwable) {
            failed = embedder == null
            return whole
        }
        // Runs of one group; a run of a single window is a flicker and joins the run before it (or after it).
        val runs = mutableListOf<MutableList<Int>>() // [group, first window, last window]
        groups.forEachIndexed { i, g -> if (runs.isNotEmpty() && runs.last()[0] == g) runs.last()[2] = i else runs.add(mutableListOf(g, i, i)) }
        var merged = true
        while (merged && runs.size > 1) {
            merged = false
            val i = runs.indexOfFirst { it[2] == it[1] }
            if (i >= 0) {
                if (i > 0) runs[i - 1][2] = runs[i][2] else runs[1][1] = runs[0][1]
                runs.removeAt(i)
                merged = true
                // Neighbours of one group become one run.
                var j = 1
                while (j < runs.size) {
                    if (runs[j][0] == runs[j - 1][0]) { runs[j - 1][2] = runs[j][2]; runs.removeAt(j) } else j++
                }
            }
        }
        if (runs.size == 1) return whole.map { it.copy(speaker = label(samples)) }
        // A change between window a (last of a run) and a + 1: halfway through their overlap, at the nearest word.
        val bounds = runs.drop(1).map { run ->
            val change = run[1] * HOP + (WINDOW - HOP) / 2
            cuts.minByOrNull { kotlin.math.abs(it - change) }!!
        }.distinct().filter { it in 1 until samples.size }.sorted()
        val edges = listOf(0) + bounds + samples.size
        return edges.zipWithNext().filter { (a, b) -> b > a }.map { (a, b) -> SpeakerPart(a, b, label(samples.copyOfRange(a, b))) }
    }

    fun close() {
        embedder?.close()
        embedder = null
    }

    companion object {
        const val MIN_SAMPLES = 24_000 // 1.5 s
        const val WINDOW = 24_000 // 1.5 s
        const val HOP = 12_000
    }
}

/** A part of a line, [start] to [end] (sample offsets in the line), said by [speaker] (null: unknown). */
data class SpeakerPart(val start: Int, val end: Int, val speaker: String?)
