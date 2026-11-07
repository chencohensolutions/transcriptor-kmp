package com.chencohensolution.transcriptor.recording

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.roundToInt

/**
 * How hard the phone's processors are working while recording, as the lecture screen shows it. Each meter is null
 * when it can't be read on this phone.
 *
 * What an app can read (probed on a Galaxy S25, SM8750, Nov 7, 2026): not the whole phone's CPU (`/proc/stat` is
 * closed to apps), but its own CPU time; the GPU's busy % for the whole phone (`kgsl-3d0/gpu_busy_percentage`); the
 * clocks; and not the NPU's load at all, so [npuOursPercent] is only the time our own models keep it busy.
 */
data class ProcessorMeters(
    /** This app's CPU use as a share of all the phone's cores (0..100): 2 cores fully busy of 8 is 25. */
    val cpuPercent: Int?,
    /** The same in cores busy (2.4), and how many the phone has. */
    val cpuCoresBusy: Float?,
    val cpuCores: Int,
    /** The GPU's busy %, for the whole phone (every app). */
    val gpuPercent: Int?,
    /** The share of time our own models run on the NPU (0..100); null when none runs there. Other apps' use is hidden. */
    val npuOursPercent: Int?,
    /** Each group of cores' clock now and at most, fastest group last. */
    val cpuClocks: List<CoreClock> = emptyList(),
    val gpuClock: Clock? = null,
    /** The NPU's temperature (its hottest sensor), in °C: a slow sign of sustained load. */
    val npuTemperatureC: Float? = null,
)

/** A clock now and its most, in MHz. */
data class Clock(val currentMhz: Int, val maxMhz: Int) {
    val percent: Int get() = if (maxMhz <= 0) 0 else (currentMhz * 100f / maxMhz).roundToInt().coerceIn(0, 100)
}

/** A group of cores sharing a clock: cores [first]..[last] (0..5 and 6..7 on SM8750). */
data class CoreClock(val first: Int, val last: Int, val clock: Clock)

/**
 * One reading of the counters the meters are worked out from. [cpuSeconds] is the app's CPU time so far (user +
 * system, all threads); [npuBusyMillis] the time our models have spent running on the NPU so far ([NpuBusyClock]).
 * Anything that couldn't be read is null, and its meter is hidden.
 */
data class ProcessorSample(
    val atMillis: Long,
    val cpuSeconds: Double?,
    val cpuCores: Int,
    val npuBusyMillis: Long? = null,
    val gpuBusyPercent: Int? = null,
    val cpuClocks: List<CoreClock> = emptyList(),
    val gpuClock: Clock? = null,
    val npuTemperatureMilliC: Int? = null,
)

/**
 * The meters between two readings: CPU and NPU use are the counters' growth over the time between them. With no
 * [previous] reading, or none earlier than [now], those are null; the others come from [now] alone.
 */
fun processorMeters(previous: ProcessorSample?, now: ProcessorSample): ProcessorMeters {
    val wallMillis = previous?.let { now.atMillis - it.atMillis }?.takeIf { it > 0 }
    val coresBusy = wallMillis?.let { wall ->
        val before = previous.cpuSeconds ?: return@let null
        val after = now.cpuSeconds ?: return@let null
        ((after - before) * 1000 / wall).toFloat().coerceIn(0f, now.cpuCores.toFloat())
    }
    val npuBusy = wallMillis?.let { wall ->
        val before = previous.npuBusyMillis ?: return@let null
        val after = now.npuBusyMillis ?: return@let null
        ((after - before) * 100f / wall).roundToInt().coerceIn(0, 100)
    }
    return ProcessorMeters(
        cpuPercent = coresBusy?.let { (it * 100 / now.cpuCores.coerceAtLeast(1)).roundToInt().coerceIn(0, 100) },
        cpuCoresBusy = coresBusy,
        cpuCores = now.cpuCores,
        gpuPercent = now.gpuBusyPercent?.coerceIn(0, 100),
        npuOursPercent = npuBusy,
        cpuClocks = now.cpuClocks.sortedBy { it.first },
        gpuClock = now.gpuClock,
        npuTemperatureC = now.npuTemperatureMilliC?.let { it / 1000f },
    )
}

/** Reads a [ProcessorSample] now (the platform's files); a field it can't read is null. */
fun interface ProcessorProbe {
    fun sample(): ProcessorSample
}

/**
 * Samples [probe] (about once a second, while the lecture screen shows meters) and keeps the last [keep] meters for
 * the graphs. [reset] when sampling stops, so a gap isn't counted as one long interval.
 */
class ProcessorMeterSampler(private val probe: ProcessorProbe, private val keep: Int = 60) {
    private var previous: ProcessorSample? = null

    /** The meters so far, oldest first; the first reading (no interval yet) isn't in it. */
    var history: List<ProcessorMeters> = emptyList()
        private set

    fun sample(): ProcessorMeters {
        val now = probe.sample()
        val meters = processorMeters(previous, now)
        if (previous != null) history = (history + meters).takeLast(keep)
        previous = now
        return meters
    }

    fun reset() {
        previous = null
        history = emptyList()
    }
}

/**
 * The time our models have kept the NPU busy: each NPU call between [begin] and [end]. Calls that overlap (Whisper
 * and voice prints at once) count once, as wall time with at least one call running. [busyMillis] is null until the
 * first call, so a recording with no NPU model shows no NPU meter.
 */
class NpuBusyClock(private val clock: () -> Long) {
    private val lock = SynchronizedObject()
    private var running = 0
    private var since = 0L
    private var done = 0L
    private var used = false

    fun begin() = synchronized(lock) {
        if (running++ == 0) since = clock()
        used = true
    }

    fun end() = synchronized(lock) {
        if (running == 0) return@synchronized
        if (--running == 0) done += clock() - since
    }

    inline fun <T> time(block: () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    val busyMillis: Long?
        get() = synchronized(lock) {
            if (!used) null else done + if (running > 0) clock() - since else 0
        }
}
