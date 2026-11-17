package com.chencohensolution.transcriptor.location

import kotlinx.serialization.Serializable

/** How much the app adds to the fixes other apps already ask for, while saving a path. */
enum class PathPrecision(val key: String) {
    /** Only the fixes other apps ask for: no GPS use of the app's own (the default, owner's call, Nov 17, 2026). */
    Passive("passive"),

    /** Plus a low-power fix about once a minute. */
    Economical("economical"),

    /** Plus GPS every second while moving, a slow walk too ([MovingSwitch]); the key from when it was "while driving". */
    WhileMoving("driving"),

    /** GPS every second, always. */
    Precise("precise");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Passive
    }
}

/** How long the path goes on after the audio stops. */
enum class KeepUntil(val key: String, val millis: Long?) {
    /** Until arriving at a saved place, at most two hours. */
    SavedPlace("place", 2 * 3_600_000L),
    Minutes30("30m", 30 * 60_000L),
    Hour1("1h", 3_600_000L),
    Hours2("2h", 2 * 3_600_000L);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SavedPlace
    }
}

/**
 * Settings › Advanced › Location (owner's request, Nov 17, 2026). Nothing here shows elsewhere, and no location
 * permission is asked for, while [stopByPlace] and [savePath] are both off (the default).
 */
@Serializable
data class LocationSettings(
    /** Stop by place: the recording stops after a countdown by the triggers below. */
    val stopByPlace: Boolean = false,
    /** Stop when moving [stopMeters] away from where the recording started. */
    val stopWhenLeaving: Boolean = true,
    val stopMeters: Int = DEFAULT_STOP_METERS,
    /** Stop when arriving at one of [places]. */
    val stopOnArrive: Boolean = false,
    val places: List<SavedPlace> = emptyList(),
    /** Stop when [device] connects (the car). */
    val stopOnDevice: Boolean = false,
    val device: StopDevice? = null,
    /** Save the moment's path. */
    val savePath: Boolean = false,
    /** [PathPrecision.key]. */
    val precision: String = PathPrecision.Passive.key,
    /** Keep saving the path after the audio stops, [KeepUntil.key] long. */
    val keepAfterStop: Boolean = false,
    val keepUntil: String = KeepUntil.SavedPlace.key,
    /** Send the path to the server with its moment, under the backup's own settings; never live. */
    val syncLocation: Boolean = false,
    /** Location permission is approximate only: leaving only at 500 m, no precise levels. */
    val approximateOnly: Boolean = false,
) {
    val pathPrecision get() = PathPrecision.of(precision)
    val keep get() = KeepUntil.of(keepUntil)

    /** Something here needs location. */
    val wantsLocation get() = stopByPlace || savePath

    /** The stop distance in use: 500 m when location is approximate only. */
    val leaveMeters get() = if (approximateOnly) STOP_METERS.last() else stopMeters.takeIf { it in STOP_METERS } ?: DEFAULT_STOP_METERS

    /** What [PlaceWatch] watches for a recording whose profile has stop by place [onForProfile]. */
    fun watchConfig(onForProfile: Boolean): PlaceWatch.Config =
        if (!stopByPlace || !onForProfile) PlaceWatch.Config()
        else PlaceWatch.Config(
            leaveMeters = leaveMeters.takeIf { stopWhenLeaving },
            arrive = places.takeIf { stopOnArrive }.orEmpty(),
            device = stopOnDevice && device != null,
        )

    companion object {
        val STOP_METERS = listOf(100, 250, 500)
        const val DEFAULT_STOP_METERS = 250
    }
}
