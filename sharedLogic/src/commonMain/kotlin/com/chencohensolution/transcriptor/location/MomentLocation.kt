package com.chencohensolution.transcriptor.location

import com.chencohensolution.transcriptor.notebook.PlaceStop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Location while one moment records (Settings › Advanced › Location): stop by place ([PlaceWatch]) and the path
 * ([PathBuilder]) fed from the same fixes, and what the app should ask the phone for besides the fixes other apps
 * already get ([request]). The platform feeds [fix], [deviceConnected] and [tick], and acts on [status]: a
 * [PlaceWatch.State.Counting] shows the countdown, a [PlaceWatch.State.Stop] stops the recording.
 *
 * Times in are by the clock (epoch ms); [toRecording] turns one into the recording's own time.
 */
class MomentLocation(
    settings: LocationSettings,
    /** Stop by place is on for this moment (the setting and its profile's). */
    stopOn: Boolean,
    /** The path is saved for this moment. */
    pathOn: Boolean,
    /** When the recording started (epoch ms): a fix from before it (a cached one) is left out. */
    private val startedAtMillis: Long = 0,
    private val toRecording: (Long) -> Long,
) {
    /** What the app asks the phone for, on top of the fixes other apps ask for (always listened to). */
    enum class Request {
        /** Nothing of our own. */
        None,

        /** A low-power fix about once a minute. */
        Minute,

        /** GPS every second. */
        EverySecond,
    }

    data class Status(
        val watch: PlaceWatch.State,
        /** The stop distance when leaving is watched; null otherwise. */
        val leaveMeters: Int?,
        /** Points kept in the path so far; null when no path is saved. */
        val points: Int?,
        val request: Request,
        /** The last fix of any accuracy, by the clock; null before the first. */
        val lastFixAt: Long?,
    ) {
        val stopping get() = watch is PlaceWatch.State.Counting
    }

    private val config = settings.watchConfig(stopOn)
    private val watch = PlaceWatch(config)
    private val path = if (pathOn && settings.savePath) PathBuilder { time(it) } else null

    /** After Stop, while the path goes on: when the audio ended (recording ms and clock), until when, and the places that end it. */
    private class Keeping(val audioEndMillis: Long, val stoppedAtClock: Long, val endsAtClock: Long, val arrival: PlaceWatch?)
    private var keeping: Keeping? = null

    /** A clock time in the recording's time: past the audio's end once it stopped. */
    private fun time(clock: Long): Long = keeping?.let { it.audioEndMillis + (clock - it.stoppedAtClock) } ?: toRecording(clock)
    private val precision = settings.pathPrecision
    private val driving = DrivingSwitch()
    private var lastGood: Fix? = null
    private var lastFixAt: Long? = null

    /**
     * The minute's fix should be a precise one (GPS waking once a minute) rather than Wi-Fi's: a 100 m stop needs
     * fixes good to 50 m, which Wi-Fi often isn't.
     */
    val minutePrecise = (config.leaveMeters ?: Int.MAX_VALUE) <= 100

    /** Anything to do at all: when false the platform listens to nothing. */
    val active = config.anything || path != null

    /** Location fixes are needed (not only the device trigger). */
    val needsFixes = config.needsFixes || path != null

    private val _status = MutableStateFlow(status(0L))
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Feeds one fix; returns the status after it. */
    fun fix(fix: Fix): Status {
        // A cached fix from before the recording (another place, maybe hours ago) is neither its place nor its path.
        if (fix.atMillis < startedAtMillis - FRESH_MILLIS) return _status.value
        lastFixAt = fix.atMillis
        if (fix.accuracyM <= 50f) lastGood = fix
        path?.add(fix)
        if (precision == PathPrecision.WhileDriving) driving.update(fix)
        val k = keeping
        if (k != null) k.arrival?.fix(fix) else watch.fix(fix)
        return publish(fix.atMillis)
    }

    fun deviceConnected(nowMillis: Long): Status {
        watch.deviceConnected(nowMillis)
        return publish(nowMillis)
    }

    /** Moves time on (every second while counting down). */
    fun tick(nowMillis: Long): Status {
        if (keeping == null) watch.tick(nowMillis)
        return publish(nowMillis)
    }

    /** "Keep recording" on the countdown. */
    fun keepRecording(nowMillis: Long): Status {
        watch.keepRecording()
        return publish(nowMillis)
    }

    /** "Cancel for this recording": stop by place does nothing more (the path goes on). */
    fun cancel(nowMillis: Long): Status {
        watch.cancel()
        return publish(nowMillis)
    }

    /** "Set the place again here": from the last good fix (a recent one), else the next. */
    fun reanchor(nowMillis: Long): Status {
        watch.reanchor(lastGood?.takeIf { nowMillis - it.atMillis < 120_000 })
        return publish(nowMillis)
    }

    /**
     * The audio stopped at [audioEndMillis] (now, [nowMillis] by the clock) and the path goes on ("Keep saving the path
     * after stopping") until [until]: a time, or arriving at one of [places] (at most [KeepUntil.SavedPlace]'s time).
     * Stop by place does nothing more. Returns false when there is no path to keep.
     */
    fun keepGoing(nowMillis: Long, audioEndMillis: Long, until: KeepUntil, places: List<SavedPlace>): Boolean {
        if (path == null) return false
        val arrival = if (until == KeepUntil.SavedPlace && places.isNotEmpty()) PlaceWatch(PlaceWatch.Config(arrive = places)) else null
        // Seen where it stopped: a place it stopped in isn't arrived at again until it has left it.
        lastGood?.let { arrival?.fix(it) }
        keeping = Keeping(audioEndMillis, nowMillis, nowMillis + (until.millis ?: 0), arrival)
        publish(nowMillis)
        return true
    }

    /** The path goes on after the audio stopped ([keepGoing]). */
    val isKeeping get() = keeping != null

    /** The path after Stop is done: its time ran out, or it arrived at a saved place. */
    fun keepingDone(nowMillis: Long): Boolean {
        val k = keeping ?: return false
        return nowMillis >= k.endsAtClock || k.arrival?.state is PlaceWatch.State.Counting || k.arrival?.state is PlaceWatch.State.Stop
    }

    /** The path with where the audio ended, for saving with the moment. */
    fun keptPath(): MomentPath? = keeping?.let { path?.path(it.audioEndMillis) }

    /** Why the recording should be kept as stopped by itself, in recording time; null unless the countdown ran out. */
    fun placeStop(): PlaceStop? = (watch.state as? PlaceWatch.State.Stop)?.let { PlaceStop(it.reason.key, toRecording(it.firedAtMillis), it.place?.name) }

    /** The path so far (null when none is saved), [audioEndMillis] set once the audio stopped. */
    fun path(audioEndMillis: Long? = null): MomentPath? = path?.path(audioEndMillis)

    /** The countdown's start in recording time, for its mark on the moment. */
    fun firedAtRecording(): Long? = (watch.state as? PlaceWatch.State.Counting)?.let { toRecording(it.firedAtMillis) }

    private fun publish(nowMillis: Long): Status = status(nowMillis).also { _status.value = it }

    private fun status(nowMillis: Long): Status {
        val w = watch.state
        return Status(
            watch = w,
            leaveMeters = config.leaveMeters.takeIf { w !is PlaceWatch.State.Off },
            points = path?.size,
            request = request(w, nowMillis),
            lastFixAt = lastFixAt,
        )
    }

    private fun request(w: PlaceWatch.State, nowMillis: Long): Request {
        keeping?.let { k ->
            val forArrival = if (k.arrival != null) Request.Minute else Request.None
            return maxOf(forArrival, pathRequest(nowMillis))
        }
        return maxOf(pathRequest(nowMillis), watchRequest(w))
    }

    private fun pathRequest(nowMillis: Long): Request = when {
        path == null -> Request.None
        precision == PathPrecision.Precise -> Request.EverySecond
        precision == PathPrecision.WhileDriving && driving.on(nowMillis) -> Request.EverySecond
        precision == PathPrecision.Economical -> Request.Minute
        else -> Request.None
    }

    private fun watchRequest(w: PlaceWatch.State): Request = when {
        !config.needsFixes || w is PlaceWatch.State.Off || w is PlaceWatch.State.Stop -> Request.None
        // A first fix to anchor to, as soon as the phone can give one.
        w is PlaceWatch.State.NoFix -> Request.EverySecond
        else -> Request.Minute
    }

    companion object {
        /** A fix up to this long before the start still counts (the phone's last one, taken as the user pressed Record). */
        const val FRESH_MILLIS = 15_000L
    }
}
