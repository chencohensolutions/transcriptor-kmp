package com.chencohensolution.transcriptor.location

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A place on Earth, in degrees. Named apart from [com.chencohensolution.transcriptor.notebook.Place], the lectures folder. */
@Serializable
data class GeoPoint(val lat: Double, val lon: Double) {
    /** The distance to [other] along the Earth's surface, in metres (haversine; good to well under 1% at these scales). */
    fun metersTo(other: GeoPoint): Double {
        val dLat = (other.lat - lat).rad
        val dLon = (other.lon - lon).rad
        val a = sin(dLat / 2).let { it * it } + cos(lat.rad) * cos(other.lat.rad) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    private val Double.rad get() = this * kotlin.math.PI / 180

    companion object {
        const val EARTH_RADIUS_M = 6_371_008.8
    }
}

/** Where a fix came from: the app's own request ([Gps], [Network]) or one another app asked for ([Passive], free for us). */
enum class FixSource(val key: String) {
    Passive("p"),
    Gps("g"),
    Network("n");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Passive
    }
}

/**
 * One location fix as the phone gave it: [atMillis] by the clock (epoch ms), [accuracyM] the radius it is good to,
 * [speedMps] and [bearingDeg] when the phone measured them (GPS does; network fixes usually don't).
 */
data class Fix(
    val atMillis: Long,
    val point: GeoPoint,
    val accuracyM: Float,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val source: FixSource = FixSource.Passive,
)

/** A place the user saved for "stop when I arrive" ([radiusM] around [point]). */
@Serializable
data class SavedPlace(val id: String, val name: String, val point: GeoPoint, val radiusM: Int = DEFAULT_RADIUS_M) {
    companion object {
        const val DEFAULT_RADIUS_M = 150
    }
}

/** A Bluetooth device whose connection stops a recording (the car), by its hardware [address]. */
@Serializable
data class StopDevice(val address: String, val name: String)
