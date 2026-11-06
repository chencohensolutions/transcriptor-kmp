package com.chencohensolution.transcriptor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.chencohensolution.transcriptor.recording.PowerProbe
import com.chencohensolution.transcriptor.recording.PowerSample
import java.io.File

/**
 * The phone's battery (BatteryManager's CURRENT_NOW in µA, and the sticky ACTION_BATTERY_CHANGED: voltage, plugged,
 * temperature, level) and the app's own CPU time (/proc/self/stat's utime + stime). On the Galaxy S25 the current is
 * negative while discharging; [PowerSample] uses its size only.
 */
class AndroidPowerProbe(context: Context) : PowerProbe {
    private val app = context.applicationContext
    private val battery = app.getSystemService(BatteryManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val ticks = Os.sysconf(OsConstants._SC_CLK_TCK).toDouble().takeIf { it > 0 } ?: 100.0

    override fun sample(): PowerSample? {
        val status = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val current = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current == Long.MIN_VALUE) return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return PowerSample(
            atMillis = SystemClock.elapsedRealtime(),
            currentMicroAmps = current,
            voltageMillivolts = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            screenOn = power.isInteractive,
            temperatureTenths = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            levelPercent = level * 100 / scale,
            cpuSeconds = cpuSeconds(),
        )
    }

    /** utime + stime of this process (fields 14 and 15 of /proc/self/stat, after the command name). */
    private fun cpuSeconds(): Double = runCatching {
        val stat = File("/proc/self/stat").readText()
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        (fields[11].toLong() + fields[12].toLong()) / ticks
    }.getOrDefault(0.0)
}
