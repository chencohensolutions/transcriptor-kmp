package com.chencohensolution.transcriptor.location

import kotlinx.serialization.Serializable

/** One point of a moment's path: [atMillis] in the recording (past its end when saved after Stop). */
data class PathPoint(
    val atMillis: Long,
    val point: GeoPoint,
    val accuracyM: Float,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val source: FixSource = FixSource.Passive,
)

/**
 * Where a moment was recorded (`path.json` beside its audio): its [points] in time order and, when the path went on
 * after the audio stopped, [audioEndMillis] (where the audio ended).
 */
data class MomentPath(val points: List<PathPoint>, val audioEndMillis: Long? = null) {
    /** The distance along the path in metres, over points good to [maxAccuracyM]. */
    fun distanceMeters(maxAccuracyM: Float = 50f): Double =
        points.filter { it.accuracyM <= maxAccuracyM }.zipWithNext { a, b -> a.point.metersTo(b.point) }.sum()

    /** The highest speed measured, in metres a second, over points good to [maxAccuracyM]; null without one. */
    fun topSpeedMps(maxAccuracyM: Float = 30f): Float? =
        points.filter { it.accuracyM <= maxAccuracyM }.mapNotNull { it.speedMps }.maxOrNull()

    val isEmpty get() = points.isEmpty()
}

/**
 * Builds a moment's path from fixes as they come: at most one point a second, and a point that didn't move (within its
 * accuracy, at least 5 m) less than a minute after the last one is left out. Fixes are by the clock; [toRecording] turns
 * a clock time into the recording's (pauses taken out by the caller).
 */
class PathBuilder(private val toRecording: (Long) -> Long) {
    private val kept = mutableListOf<PathPoint>()
    private var lastClock: Long? = null

    /** Adds [fix] unless it is thinned out; true when kept. */
    fun add(fix: Fix): Boolean {
        val last = kept.lastOrNull()
        val lastAt = lastClock
        if (last != null && lastAt != null) {
            val dt = fix.atMillis - lastAt
            if (dt < 1_000) return false
            // Moving, even at a slow walk (GPS's own speed, good to a fraction of a m/s): every point counts.
            val moving = (fix.speedMps ?: 0f) >= SLOW_WALK_MPS && fix.accuracyM <= 30f
            val still = !moving && last.point.metersTo(fix.point) < maxOf(5f, fix.accuracyM, last.accuracyM)
            if (still && dt < 60_000) return false
        }
        kept += PathPoint(toRecording(fix.atMillis), fix.point, fix.accuracyM, fix.speedMps, fix.bearingDeg, fix.source)
        lastClock = fix.atMillis
        return true
    }

    val size get() = kept.size

    fun path(audioEndMillis: Long? = null) = MomentPath(kept.toList(), audioEndMillis)

    companion object {
        /** About 1 km/h: slower than this, a point has to move to be kept. */
        const val SLOW_WALK_MPS = 0.3f
    }
}

/**
 * "Precise while moving" (owner's request, Nov 17, 2026: precise at a slow walk too, not only driving): GPS every
 * second from the moment the phone moves, off again [offAfterMillis] after it last did. Moving is a fix whose measured
 * speed is at least [onAboveMps] (about 1.4 km/h, a slow walk), a good fix [moveMeters] or more from where it stood, or
 * the phone's motion sensor ([motion]: it wakes on the first steps, costing nothing while standing).
 */
class MovingSwitch(
    private val onAboveMps: Float = 0.4f,
    private val offAfterMillis: Long = 120_000,
    private val moveMeters: Double = 15.0,
) {
    private var movedAt: Long? = null
    private var stoodAt: GeoPoint? = null

    /** Feeds [fix]; returns whether GPS should run every second now. */
    fun update(fix: Fix): Boolean {
        val speed = fix.speedMps
        if (speed != null && speed >= onAboveMps && fix.accuracyM <= 30f) movedAt = fix.atMillis
        if (fix.accuracyM <= 50f) {
            val stood = stoodAt
            if (stood == null) stoodAt = fix.point
            else if (stood.metersTo(fix.point) >= maxOf(moveMeters, fix.accuracyM * 2.0)) { movedAt = fix.atMillis; stoodAt = fix.point }
        }
        return on(fix.atMillis)
    }

    /** The phone's motion sensor felt it start moving at [nowMillis]. */
    fun motion(nowMillis: Long) { movedAt = nowMillis }

    fun on(nowMillis: Long): Boolean = movedAt?.let { nowMillis - it < offAfterMillis } ?: false
}

/** `path.json`, version 1: the points as columns (compact: an hour a second is about 3,600 rows). */
@Serializable
internal data class PathFileV1(
    val v: Int = 1,
    /** Times in the recording (ms). */
    val t: List<Long> = emptyList(),
    val lat: List<Double> = emptyList(),
    val lon: List<Double> = emptyList(),
    /** Accuracy (m). */
    val acc: List<Float> = emptyList(),
    /** Speed (m/s); null where not measured. */
    val speed: List<Float?> = emptyList(),
    /** Bearing (degrees); null where not measured. */
    val bearing: List<Float?> = emptyList(),
    /** [FixSource.key] per point. */
    val src: String = "",
    val audioEndMs: Long? = null,
) {
    fun toPath(): MomentPath {
        val n = minOf(t.size, lat.size, lon.size, acc.size)
        return MomentPath(
            (0 until n).map { i ->
                PathPoint(t[i], GeoPoint(lat[i], lon[i]), acc[i], speed.getOrNull(i), bearing.getOrNull(i), FixSource.of(src.getOrNull(i)?.toString()))
            },
            audioEndMs,
        )
    }

    companion object {
        fun of(path: MomentPath) = PathFileV1(
            t = path.points.map { it.atMillis },
            lat = path.points.map { it.point.lat },
            lon = path.points.map { it.point.lon },
            acc = path.points.map { it.accuracyM },
            speed = path.points.map { it.speedMps },
            bearing = path.points.map { it.bearingDeg },
            src = path.points.joinToString("") { it.source.key },
            audioEndMs = path.audioEndMillis,
        )
    }
}
