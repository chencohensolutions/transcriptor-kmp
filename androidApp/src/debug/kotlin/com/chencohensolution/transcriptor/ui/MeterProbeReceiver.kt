package com.chencohensolution.transcriptor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chencohensolution.transcriptor.recording.AndroidProcessorProbe
import com.chencohensolution.transcriptor.recording.processorMeters
import java.io.File
import kotlin.concurrent.thread

/**
 * Checks the processor meters' files from inside an app (the UI lab app only, as an ordinary app, untrusted_app), with
 * no screen: `adb shell am broadcast -n com.chencohensolution.transcriptor.uilab/com.chencohensolution.transcriptor.ui.MeterProbeReceiver`,
 * then `adb logcat -s MeterProbe`. `run-as` runs in another SELinux domain (runas_app), so it can't tell what an app may read.
 */
class MeterProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                Log.i(TAG, "domain: ${runCatching { File("/proc/self/attr/current").readText().trim() }.getOrElse { it.toString() }}")
                val probe = AndroidProcessorProbe { null }
                val first = probe.sample()
                // Some CPU work, so this process's CPU time moves.
                var x = 0.0
                val until = System.nanoTime() + 300_000_000
                while (System.nanoTime() < until) x += Math.sqrt(x + 1)
                Thread.sleep(700)
                val second = probe.sample()
                Log.i(TAG, "first: $first")
                Log.i(TAG, "second: $second")
                Log.i(TAG, "meters: ${processorMeters(first, second)} ($x)")
            } catch (e: Throwable) {
                Log.e(TAG, "probe failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MeterProbe"
    }
}
