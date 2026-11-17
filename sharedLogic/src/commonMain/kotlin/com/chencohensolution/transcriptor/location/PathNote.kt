package com.chencohensolution.transcriptor.location

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * A moment's path as it rides in its note on the server (Sync location, owner's request, Nov 17, 2026): the note is
 * limited to 256 KiB, so the points are whole numbers, each the change from the one before (time in 0.1 s, position in
 * 1e-6 degrees, about 11 cm), accuracy in metres, speed in 0.1 m/s (-1 unmeasured), bearing in degrees (-1 unmeasured).
 * An hour at a point a second is about 100 KB; a longer one is thinned ([of]) until it fits.
 */
@Serializable
data class PathNoteV1(
    val v: Int = 1,
    val t: List<Long> = emptyList(),
    val lat: List<Long> = emptyList(),
    val lon: List<Long> = emptyList(),
    val acc: List<Int> = emptyList(),
    val spd: List<Int> = emptyList(),
    val brg: List<Int> = emptyList(),
    val src: String = "",
    val end: Long? = null,
) {
    fun toPath(): MomentPath {
        val n = minOf(t.size, lat.size, lon.size)
        var tt = 0L; var la = 0L; var lo = 0L
        val points = (0 until n).map { i ->
            tt += t[i]; la += lat[i]; lo += lon[i]
            PathPoint(
                atMillis = tt * 100,
                point = GeoPoint(la / 1e6, lo / 1e6),
                accuracyM = acc.getOrElse(i) { 1000 }.toFloat(),
                speedMps = spd.getOrElse(i) { -1 }.takeIf { it >= 0 }?.let { it / 10f },
                bearingDeg = brg.getOrElse(i) { -1 }.takeIf { it >= 0 }?.toFloat(),
                source = FixSource.of(src.getOrNull(i)?.toString()),
            )
        }
        return MomentPath(points, end)
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        /** [path] made to fit in [maxBytes] of JSON: every point, else every 2nd, 3rd… (the first and last kept). */
        fun of(path: MomentPath, maxBytes: Int = MAX_BYTES): PathNoteV1 {
            var step = 1
            while (true) {
                val kept = path.points.filterIndexed { i, _ -> i % step == 0 || i == path.points.lastIndex }
                val note = encode(kept, path.audioEndMillis)
                if (step >= path.points.size || json.encodeToString(note).length <= maxBytes) return note
                step++
            }
        }

        private fun encode(points: List<PathPoint>, end: Long?): PathNoteV1 {
            var pt = 0L; var pla = 0L; var plo = 0L
            val t = ArrayList<Long>(points.size); val lat = ArrayList<Long>(points.size); val lon = ArrayList<Long>(points.size)
            for (p in points) {
                val tt = p.atMillis / 100
                val la = (p.point.lat * 1e6).roundToLong()
                val lo = (p.point.lon * 1e6).roundToLong()
                t += tt - pt; lat += la - pla; lon += lo - plo
                pt = tt; pla = la; plo = lo
            }
            return PathNoteV1(
                t = t, lat = lat, lon = lon,
                acc = points.map { it.accuracyM.toInt() },
                spd = points.map { s -> s.speedMps?.let { (it * 10).toInt() } ?: -1 },
                brg = points.map { b -> b.bearingDeg?.toInt() ?: -1 },
                src = points.joinToString("") { it.source.key },
                end = end,
            )
        }

        /** What the path may take of the note (the rest is the transcript and notes): well under the server's 256 KiB. */
        const val MAX_BYTES = 160_000
    }
}
