package com.chencohensolution.transcriptor.recording

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * One reading of the phone's battery and of the app's process. [currentMicroAmps] is the battery's current as Android
 * reports it (its sign varies by phone: only the size is used, and readings while [charging] don't count).
 * [cpuSeconds] is the app's own CPU time so far (user + system).
 */
data class PowerSample(
    val atMillis: Long,
    val currentMicroAmps: Long,
    val voltageMillivolts: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    val temperatureTenths: Int,
    val levelPercent: Int,
    val cpuSeconds: Double,
)

/** Reads a [PowerSample] (the platform's battery and process); null when it can't. */
fun interface PowerProbe {
    fun sample(): PowerSample?
}

/** A model a lecture used and where: "NPU" or "CPU" (or "CPU after NPU failed"). */
@Serializable
data class PowerModel(val role: String, val name: String, val runsOn: String)

/**
 * How much power a lecture's recording took, from [PowerMeter]'s samples, as `power.json` keeps it (version 1).
 *
 * - The battery's power is the whole phone's, screen and other apps included; the app's own share shows in
 *   [cpuSecondsPerMinute] (the process's CPU time).
 * - When [charging] (any sample on a charger), the power figures are null: the current then isn't what the phone uses.
 */
@Serializable
data class PowerReport(
    val v: Int = 1,
    val durationMillis: Long,
    val samples: Int,
    val averageMilliwatts: Double?,
    val totalMilliwattHours: Double?,
    val milliwattHoursPerMinute: Double?,
    val joulesPerMinute: Double?,
    val batteryPercentStart: Int?,
    val batteryPercentEnd: Int?,
    val temperatureStart: Double?,
    val temperatureEnd: Double?,
    val cpuSecondsPerMinute: Double?,
    val screenOnShare: Double?,
    val charging: Boolean,
    val models: List<PowerModel> = emptyList(),
) {
    /** The temperature's change over the lecture, °C. */
    val temperatureRise get() = if (temperatureStart != null && temperatureEnd != null) temperatureEnd - temperatureStart else null

    companion object {
        /** The report of [samples] over [durationMillis] of recording, with [models]. */
        fun of(samples: List<PowerSample>, durationMillis: Long, models: List<PowerModel>): PowerReport {
            val charging = samples.any { it.charging }
            val watts = samples.filter { !it.charging }.map { abs(it.currentMicroAmps) / 1e6 * it.voltageMillivolts / 1e3 }
            val avgMw = if (charging || watts.isEmpty()) null else watts.average() * 1000
            val minutes = durationMillis / 60_000.0
            val hours = durationMillis / 3_600_000.0
            val cpu = if (samples.size >= 2 && minutes > 0) (samples.last().cpuSeconds - samples.first().cpuSeconds) / minutes else null
            return PowerReport(
                durationMillis = durationMillis,
                samples = samples.size,
                averageMilliwatts = avgMw?.round(1),
                totalMilliwattHours = avgMw?.let { (it * hours).round(2) },
                milliwattHoursPerMinute = avgMw?.let { (it / 60).round(2) },
                joulesPerMinute = avgMw?.let { (it * 60 / 1000).round(1) },
                batteryPercentStart = samples.firstOrNull()?.levelPercent,
                batteryPercentEnd = samples.lastOrNull()?.levelPercent,
                temperatureStart = samples.firstOrNull()?.temperatureTenths?.let { it / 10.0 },
                temperatureEnd = samples.lastOrNull()?.temperatureTenths?.let { it / 10.0 },
                cpuSecondsPerMinute = cpu?.round(2),
                screenOnShare = if (samples.isEmpty()) null else (samples.count { it.screenOn }.toDouble() / samples.size).round(2),
                charging = charging,
                models = models,
            )
        }

        private fun Double.round(places: Int): Double {
            var f = 1.0
            repeat(places) { f *= 10 }
            return kotlin.math.round(this * f) / f
        }
    }
}

/**
 * Samples a [PowerProbe] every [intervalMillis] while a lecture records ([start] to [stop]), on [scope]. Paused
 * recording still counts: the phone is still on.
 */
class PowerMeter(private val probe: PowerProbe, private val intervalMillis: Long = 10_000) {
    private val lock = SynchronizedObject()
    private val samples = mutableListOf<PowerSample>()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        synchronized(lock) { samples.clear() }
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                runCatching { probe.sample() }.getOrNull()?.let { s -> synchronized(lock) { samples += s } }
                delay(intervalMillis)
            }
        }
    }

    /** Stops sampling (with a last sample) and gives the samples. */
    fun stop(): List<PowerSample> {
        job?.cancel()
        job = null
        runCatching { probe.sample() }.getOrNull()?.let { s -> synchronized(lock) { samples += s } }
        return synchronized(lock) { samples.toList() }
    }
}

/** A row of `power-log.csv` (the app's files folder): one per lecture, the header first. */
object PowerLogCsv {
    const val HEADER = "lecture,notebook,started,duration_s,avg_mw,mwh_total,mwh_per_min,j_per_min,battery_start,battery_end,temp_start,temp_end,cpu_s_per_min,screen_on,charging,models"

    fun row(lectureId: String, labels: String, startedAtMillis: Long, r: PowerReport): String = listOf(
        lectureId, labels, startedAtMillis.toString(), (r.durationMillis / 1000).toString(),
        r.averageMilliwatts.str(), r.totalMilliwattHours.str(), r.milliwattHoursPerMinute.str(), r.joulesPerMinute.str(),
        r.batteryPercentStart?.toString().orEmpty(), r.batteryPercentEnd?.toString().orEmpty(),
        r.temperatureStart.str(), r.temperatureEnd.str(), r.cpuSecondsPerMinute.str(), r.screenOnShare.str(),
        r.charging.toString(),
        "\"" + r.models.joinToString("; ") { "${it.role}: ${it.name} (${it.runsOn})" }.replace("\"", "'") + "\"",
    ).joinToString(",")

    private fun Double?.str() = this?.toString().orEmpty()
}
