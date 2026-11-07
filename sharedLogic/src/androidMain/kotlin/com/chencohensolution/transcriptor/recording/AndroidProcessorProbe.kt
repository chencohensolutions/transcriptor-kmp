package com.chencohensolution.transcriptor.recording

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * The processors as an app can read them on Android (checked on a Galaxy S25, SM8750, Nov 7, 2026); any file it
 * can't read leaves its field null, and that meter is hidden:
 * - CPU: this app's time from `/proc/self/stat` (utime + stime). The whole phone's (`/proc/stat`) is closed to apps.
 * - Clocks: each `cpufreq/policy*` (its cores, `scaling_cur_freq`, `cpuinfo_max_freq`, in kHz); the GPU's
 *   `kgsl-3d0/devfreq/cur_freq` and `max_gpuclk` (Hz). Qualcomm's file names; other chips have no GPU meter.
 * - GPU busy: `kgsl-3d0/gpu_busy_percentage` ("4 %"), for the whole phone.
 * - NPU: nothing to read; [npuBusyMillis] is our own models' time on it. Its temperature: the hottest thermal zone
 *   whose type starts with "nsp" (nsphvx-0…, nsphmx-0…), found once.
 */
class AndroidProcessorProbe(private val npuBusyMillis: () -> Long?) : ProcessorProbe {
    private val ticks = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK).toDouble() }.getOrNull()?.takeIf { it > 0 } ?: 100.0
    private val cores = possibleCores() ?: Runtime.getRuntime().availableProcessors()
    private val policies = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }.orEmpty().toList()
    private val npuZones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
        .filter { read(File(it, "type"))?.startsWith("nsp") == true }

    override fun sample() = ProcessorSample(
        atMillis = SystemClock.elapsedRealtime(),
        cpuSeconds = cpuSeconds(),
        cpuCores = cores,
        npuBusyMillis = npuBusyMillis(),
        gpuBusyPercent = read(File(GPU, "gpu_busy_percentage"))?.takeWhile { it.isDigit() }?.toIntOrNull(),
        cpuClocks = policies.mapNotNull { coreClock(it) },
        gpuClock = gpuClock(),
        npuTemperatureMilliC = npuZones.mapNotNull { read(File(it, "temp"))?.toIntOrNull() }.maxOrNull(),
    )

    /** utime + stime (fields 14 and 15 of /proc/self/stat, counted after the command name's closing parenthesis). */
    private fun cpuSeconds(): Double? = read(File("/proc/self/stat"))?.let { stat ->
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        val user = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val system = fields.getOrNull(12)?.toLongOrNull() ?: return null
        (user + system) / ticks
    }

    private fun coreClock(policy: File): CoreClock? {
        val cpus = read(File(policy, "related_cpus"))?.split(' ')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() } ?: return null
        val now = read(File(policy, "scaling_cur_freq"))?.toLongOrNull() ?: return null
        val max = read(File(policy, "cpuinfo_max_freq"))?.toLongOrNull() ?: return null
        return CoreClock(cpus.min(), cpus.max(), Clock((now / 1000).toInt(), (max / 1000).toInt()))
    }

    private fun gpuClock(): Clock? {
        val now = read(File(GPU, "devfreq/cur_freq"))?.toLongOrNull() ?: return null
        val max = read(File(GPU, "max_gpuclk"))?.toLongOrNull() ?: return null
        return Clock((now / 1_000_000).toInt(), (max / 1_000_000).toInt())
    }

    /** "0-7" or "0-3,6-7": how many cores the phone has, online or not. */
    private fun possibleCores(): Int? = read(File("/sys/devices/system/cpu/possible"))?.split(',')?.sumOf { range ->
        val ends = range.split('-').map { it.toIntOrNull() ?: return null }
        ends.last() - ends.first() + 1
    }?.takeIf { it > 0 }

    private companion object {
        val GPU = File("/sys/class/kgsl/kgsl-3d0")

        /** A small file's text, trimmed; null when it is missing or the app may not read it. */
        fun read(file: File): String? = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
