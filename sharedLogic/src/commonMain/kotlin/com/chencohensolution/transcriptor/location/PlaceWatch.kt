package com.chencohensolution.transcriptor.location

import kotlin.math.max

/** Why a recording stopped by itself (kept on the moment, [com.chencohensolution.transcriptor.notebook.Lecture.placeStop]). */
enum class StopReason(val key: String) {
    /** It moved away from where the recording started. */
    Left("left"),

    /** It arrived at a saved place. */
    Arrived("arrived"),

    /** The chosen Bluetooth device (the car) connected. */
    Device("device"),

    /** It came back to where "Stop when back here" was turned on, after going away. */
    Returned("returned");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * Stop by place while one moment records (owner's request, Nov 17, 2026): decides from the fixes it is given when the
 * recording should stop, with a countdown the user can cancel. Times are by the clock (epoch ms); the caller turns the
 * trigger's time into the recording's own.
 *
 * - **Leaving:** the first good fix is the anchor. Two good fixes in a row beyond [Config.leaveMeters], at least
 *   [Config.dwellMillis] apart, trigger it; a good fix back inside starts over. Indoor drift rarely does both.
 * - **Arriving:** a saved place is armed only once a good fix is seen outside it, so a recording that starts at home
 *   doesn't stop at home. Then two good fixes inside it, [Config.dwellMillis] apart, trigger it.
 * - **Device:** [deviceConnected] triggers it at once.
 * - **Back here:** turned on while recording ([setReturning]), where the phone is then is the spot. It is armed once a
 *   good fix is [RETURN_ARM_METERS] away, then two good fixes within [RETURN_METERS] of it, [Config.dwellMillis] apart,
 *   trigger it.
 *
 * Leaving and back here can be turned on and off while recording ([setLeaving], [setReturning]).
 *
 * A fix is good when its accuracy is within half the leave distance (at most 75 m when only arriving is on).
 * A trigger starts the countdown ([Config.countdownMillis]); [tick] says when it ran out, [keepRecording] cancels it and
 * disarms that trigger for the rest of the recording.
 */
class PlaceWatch(private val config: Config) {
    data class Config(
        /** Stop this far from the anchor; null: leaving is off. */
        val leaveMeters: Int? = null,
        /** The saved places to stop at; empty: arriving is off. */
        val arrive: List<SavedPlace> = emptyList(),
        val device: Boolean = false,
        val dwellMillis: Long = 60_000,
        val countdownMillis: Long = 60_000,
    ) {
        val anything get() = leaveMeters != null || arrive.isNotEmpty() || device
        val needsFixes get() = leaveMeters != null || arrive.isNotEmpty()
    }

    /** What the watch is doing, for the status line. */
    sealed interface State {
        /** Waiting for a first good fix to anchor to (leaving), or nothing to watch by location. */
        data object NoFix : State

        /** Armed: [anchor] is where leaving is measured from (null when only arriving or the device is on). */
        data class Watching(val anchor: GeoPoint?) : State

        /** Counting down to a stop that [endsAtMillis]; [firedAtMillis] is when the trigger was true. */
        data class Counting(val reason: StopReason, val firedAtMillis: Long, val endsAtMillis: Long, val place: SavedPlace? = null) : State

        /** The countdown ran out: the recording should stop now. */
        data class Stop(val reason: StopReason, val firedAtMillis: Long, val place: SavedPlace? = null) : State

        /** Every trigger was cancelled for this recording. */
        data object Off : State
    }

    private var leaveOn = config.leaveMeters != null
    private var leaveM: Int = config.leaveMeters ?: LocationSettings.DEFAULT_STOP_METERS
    private var returnOn = false
    private var returnSpot: GeoPoint? = null
    private var returnArmed = false
    private var backSince: Long? = null
    private var arriveOn = config.arrive.isNotEmpty()
    private var deviceOn = config.device
    private var anchor: GeoPoint? = null
    private var outsideSince: Long? = null
    private val armedPlaces = mutableSetOf<String>()
    private val insideSince = mutableMapOf<String, Long>()

    var state: State = initial()
        private set

    private fun initial(): State = when {
        !leaveOn && !arriveOn && !deviceOn && !returnOn -> State.Off
        leaveOn && anchor == null || returnOn && returnSpot == null -> State.NoFix
        else -> State.Watching(anchor.takeIf { leaveOn })
    }

    /** Stop when leaving is on, [leaveMeters] from the anchor. */
    val leaving get() = leaveOn
    val leaveMeters: Int? get() = leaveM.takeIf { leaveOn }

    /** Stop when back here is on. */
    val returning get() = returnOn

    /** Fixes are needed: leaving, arriving or back here is on. */
    val needsFixes get() = leaveOn || arriveOn || returnOn

    private val goodAccuracyM: Double
        get() = if (leaveOn) leaveM / 2.0 else 75.0

    /** Feeds one fix; returns the new state (a [State.Counting] when this fix triggered a stop). */
    fun fix(fix: Fix): State {
        if (state is State.Counting || state is State.Stop || state is State.Off) return state
        if (returnOn && fix.accuracyM <= RETURN_METERS) {
            val spot = returnSpot
            if (spot == null) {
                returnSpot = fix.point
                if (state == State.NoFix && !(leaveOn && anchor == null)) state = State.Watching(anchor.takeIf { leaveOn })
            } else {
                val d = spot.metersTo(fix.point)
                if (d > RETURN_ARM_METERS) { returnArmed = true; backSince = null }
                else if (d <= RETURN_METERS && returnArmed) {
                    val since = backSince ?: fix.atMillis.also { backSince = it }
                    if (fix.atMillis - since >= config.dwellMillis) return fire(StopReason.Returned, fix.atMillis)
                } else backSince = null
            }
        }
        if (fix.accuracyM > goodAccuracyM) return state
        if (leaveOn) {
            val a = anchor
            if (a == null) {
                anchor = fix.point
                if (!(returnOn && returnSpot == null)) state = State.Watching(fix.point)
            } else if (a.metersTo(fix.point) > leaveM) {
                val since = outsideSince
                if (since == null) outsideSince = fix.atMillis
                else if (fix.atMillis - since >= config.dwellMillis) return fire(StopReason.Left, fix.atMillis)
            } else {
                outsideSince = null
            }
        }
        if (arriveOn) {
            for (place in config.arrive) {
                val d = place.point.metersTo(fix.point)
                if (d > place.radiusM + max(25.0, fix.accuracyM.toDouble())) {
                    armedPlaces += place.id
                    insideSince.remove(place.id)
                } else if (d <= place.radiusM && place.id in armedPlaces) {
                    val since = insideSince.getOrPut(place.id) { fix.atMillis }
                    if (fix.atMillis - since >= config.dwellMillis) return fire(StopReason.Arrived, fix.atMillis, place)
                }
            }
            if (state == State.NoFix && !leaveOn) state = State.Watching(null)
        }
        return state
    }

    /** The chosen Bluetooth device connected at [nowMillis]. */
    fun deviceConnected(nowMillis: Long): State =
        if (deviceOn && (state is State.NoFix || state is State.Watching)) fire(StopReason.Device, nowMillis) else state

    /** Moves time on: a countdown that ran out by [nowMillis] becomes [State.Stop]. */
    fun tick(nowMillis: Long): State {
        val s = state
        if (s is State.Counting && nowMillis >= s.endsAtMillis) state = State.Stop(s.reason, s.firedAtMillis, s.place)
        return state
    }

    /** "Keep recording": cancels the countdown and disarms the trigger that fired, for the rest of this recording. */
    fun keepRecording(): State {
        val s = state as? State.Counting ?: return state
        when (s.reason) {
            StopReason.Left -> leaveOn = false
            StopReason.Arrived -> arriveOn = false
            StopReason.Device -> deviceOn = false
            StopReason.Returned -> returnOn = false
        }
        outsideSince = null
        insideSince.clear()
        backSince = null
        state = initial()
        return state
    }

    /**
     * "Stop when leaving" turned on or off while recording: on, leaving is measured [meters] from [here] (null: from
     * the next good fix). Off while its countdown runs, the countdown ends.
     */
    fun setLeaving(on: Boolean, meters: Int, here: Fix?) {
        if (state is State.Stop) return
        val counting = state as? State.Counting
        leaveOn = on
        leaveM = meters
        anchor = null
        outsideSince = null
        if (counting == null || counting.reason == StopReason.Left) {
            state = initial()
            if (on && here != null) fix(here)
        }
    }

    /**
     * "Stop when back here" turned on or off while recording: on, [here] is the spot (null: the next good fix). Off
     * while its countdown runs, the countdown ends.
     */
    fun setReturning(on: Boolean, here: Fix?) {
        if (state is State.Stop) return
        val counting = state as? State.Counting
        returnOn = on
        returnSpot = here?.takeIf { on && it.accuracyM <= RETURN_METERS }?.point
        returnArmed = false
        backSince = null
        if (counting == null || counting.reason == StopReason.Returned) state = initial()
    }

    /** "Set the place again here": leaving is measured from [here] from now on (null: from the next good fix). */
    fun reanchor(here: Fix?) {
        if (!leaveOn || state is State.Counting || state is State.Stop) return
        anchor = null
        outsideSince = null
        state = State.NoFix
        if (here != null) fix(here)
    }

    /** "Cancel for this recording": nothing will stop it. */
    fun cancel() {
        leaveOn = false; arriveOn = false; deviceOn = false; returnOn = false
        state = State.Off
    }

    companion object {
        /** "Back here" counts within this of the spot, with fixes this good. */
        const val RETURN_METERS = 50f

        /** "Back here" is armed once this far from the spot: a walk away, not the fixes' drift. */
        const val RETURN_ARM_METERS = 150.0
    }

    private fun fire(reason: StopReason, at: Long, place: SavedPlace? = null): State {
        state = State.Counting(reason, at, at + config.countdownMillis, place)
        return state
    }
}
