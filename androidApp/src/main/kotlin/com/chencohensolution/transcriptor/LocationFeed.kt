package com.chencohensolution.transcriptor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import androidx.core.content.ContextCompat
import com.chencohensolution.transcriptor.location.Fix
import com.chencohensolution.transcriptor.location.FixSource
import com.chencohensolution.transcriptor.location.GeoPoint
import com.chencohensolution.transcriptor.location.MomentLocation
import com.chencohensolution.transcriptor.location.StopDevice
import com.chencohensolution.transcriptor.ui.LocationAccess

/**
 * Location for one moment, from Android's own [LocationManager] (no Play services): always the fixes other apps ask
 * for (the passive provider, free for us), plus the app's own requests as [request] says (a low-power fix a minute, or
 * GPS every second), and the chosen Bluetooth device's connection. Callbacks come on the main thread.
 */
class LocationFeed(
    private val context: Context,
    private val onFix: (Fix) -> Unit,
    private val onDevice: () -> Unit,
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val executor = ContextCompat.getMainExecutor(context)
    private var passive: LocationListener? = null
    private var own: LocationListener? = null
    private var ownRequest = MomentLocation.Request.None
    private var receiver: BroadcastReceiver? = null

    /** Starts listening: to fixes when [fixes], and to [device] connecting when given. */
    @SuppressLint("MissingPermission")
    fun start(fixes: Boolean, device: StopDevice?) {
        if (fixes && manager != null && locationAccess(context) != LocationAccess.None && passive == null) {
            val l = LocationListener { onFix(it.toFix(FixSource.Passive)) }
            runCatching {
                manager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL).setMinUpdateIntervalMillis(1_000).build(),
                    executor, l,
                )
                passive = l
            }.onFailure { android.util.Log.w("LocationFeed", "passive listening refused", it) }
        }
        if (device != null && receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    if (d.address.equals(device.address, ignoreCase = true)) onDevice()
                }
            }
            runCatching {
                ContextCompat.registerReceiver(context, r, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED), ContextCompat.RECEIVER_EXPORTED)
                receiver = r
            }.onFailure { android.util.Log.w("LocationFeed", "Bluetooth not watched", it) }
        }
    }

    /** Asks the phone for [request] on top of the passive fixes (a change re-registers; the same does nothing). */
    @SuppressLint("MissingPermission")
    fun request(request: MomentLocation.Request, minutePrecise: Boolean = false) {
        if (request == ownRequest || manager == null) return
        own?.let { manager.removeUpdates(it) }
        own = null
        ownRequest = request
        if (request == MomentLocation.Request.None || locationAccess(context) == LocationAccess.None) return
        val l = LocationListener { location ->
            onFix(location.toFix(if (location.provider == LocationManager.GPS_PROVIDER || location.hasSpeed()) FixSource.Gps else FixSource.Network))
        }
        val built = when (request) {
            MomentLocation.Request.EverySecond -> LocationRequest.Builder(1_000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
            else -> LocationRequest.Builder(60_000)
                .setQuality(if (minutePrecise) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                .setMinUpdateIntervalMillis(30_000).build()
        }
        runCatching {
            // Every second straight from GPS (speed and bearing, and the same fixes the passive listener hears, so no
            // second, smoothed track zigzags beside it); the minute's from the fused provider (Wi-Fi when it is enough).
            val provider = if (request == MomentLocation.Request.EverySecond && manager.hasProvider(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else bestProvider(manager)
            manager.requestLocationUpdates(provider, built, executor, l)
            own = l
        }.onFailure { android.util.Log.w("LocationFeed", "location request refused", it) }
    }

    /** Stops listening to everything. */
    fun stop() {
        passive?.let { manager?.removeUpdates(it) }
        own?.let { manager?.removeUpdates(it) }
        passive = null
        own = null
        ownRequest = MomentLocation.Request.None
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    // By its age, not its own clock: a fix's time comes from GPS or the network and may be off from the phone's.
    private fun Location.toFix(source: FixSource) = Fix(
        atMillis = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000,
        point = GeoPoint(latitude, longitude),
        accuracyM = if (hasAccuracy()) accuracy else 1_000f,
        speedMps = speed.takeIf { hasSpeed() },
        bearingDeg = bearing.takeIf { hasBearing() },
        source = source,
    )
}
