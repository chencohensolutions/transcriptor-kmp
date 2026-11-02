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

/** A person whose voice the notebook knows, from a lecture where the user named them. */
data class Voice(val name: String, val print: List<Float>)

/**
 * Tells speakers apart during a lecture, a line at a time: each line's voice print joins the speaker whose average
 * print is closest, if it is at least [threshold] alike (cosine), else starts a new speaker ("speaker-N", named by
 * the user later). Voices the notebook knows come first, under their names, but join the lecture's speakers only
 * once they speak. Thread-safe: lines come from the transcription worker, renames from the UI.
 */
class SpeakerTracker(known: List<Voice> = emptyList(), private val threshold: Float = THRESHOLD) {
    private class Cluster(val voiceName: String?, var sum: FloatArray, var lines: Int, var id: String? = null)

    private val lock = SynchronizedObject()
    private val clusters = known.map { Cluster(it.name, normalized(it.print.toFloatArray()), 1) }.toMutableList()
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

    /** The average voice print of speaker [id], to remember them in the notebook; null if they never spoke. */
    fun print(id: String): List<Float>? = synchronized(lock) { clusters.firstOrNull { it.id == id }?.let { normalized(it.sum).toList() } }

    companion object {
        /** Cosine similarity at which two CAM++ prints count as one speaker (same-speaker pairs are mostly above). */
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

    fun close() {
        embedder?.close()
        embedder = null
    }

    companion object {
        const val MIN_SAMPLES = 24_000 // 1.5 s
    }
}
