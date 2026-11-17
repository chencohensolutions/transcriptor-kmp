package com.chencohensolution.transcriptor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.transcriptor.location.GeoPoint
import com.chencohensolution.transcriptor.location.LocationSettings
import com.chencohensolution.transcriptor.location.SavedPlace
import com.chencohensolution.transcriptor.location.StopDevice
import com.chencohensolution.transcriptor.ui.LocationAccess
import com.chencohensolution.transcriptor.ui.LocationActions
import com.chencohensolution.transcriptor.ui.LocationPage
import com.chencohensolution.transcriptor.ui.LocationUi

/** What the phone lets the app know of its location now. */
fun locationAccess(context: Context): LocationAccess = when {
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationAccess.Precise
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationAccess.Approximate
    else -> LocationAccess.None
}

/** The phone's paired Bluetooth devices, by name; null without the Bluetooth permission. */
@SuppressLint("MissingPermission")
fun pairedDevices(context: Context): List<StopDevice>? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
    val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return emptyList()
    return runCatching { adapter.bondedDevices.map { StopDevice(it.address, it.name ?: it.address) }.sortedBy { it.name } }.getOrDefault(emptyList())
}

/** The location provider to ask for one fix or for updates: Android's fused provider, else GPS. */
fun bestProvider(manager: LocationManager): String =
    if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER

/**
 * Settings › Advanced › Location, fed from [Recording.settings]: turning stop by place or the path on asks for location
 * (precise or approximate; "while using the app" is enough, the recording runs in a foreground service), the Bluetooth
 * permission is asked for only when the device trigger is turned on, and "Add the current place" takes one fix.
 */
@Composable
fun LocationSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = Recording.settings
    var settings by remember { mutableStateOf(store.location) }
    var access by remember { mutableStateOf(locationAccess(context)) }
    var devices by remember { mutableStateOf(pairedDevices(context)) }
    var adding by remember { mutableStateOf(false) }
    var addFailed by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    val server by Recording.backup.state.collectAsStateWithLifecycle()
    val paired = server.paired != null

    fun change(f: (LocationSettings) -> LocationSettings) {
        val before = store.location.syncLocation
        store.updateLocation(f)
        settings = store.location
        // Paths go into (or leave) the moments' notes on the server at the next sync.
        if (settings.syncLocation != before) runCatching { Recording.backup.syncPathChanged() }
    }

    /** Turns on what [pending] says once location is allowed. */
    var pending by remember { mutableStateOf<((LocationSettings) -> LocationSettings)?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = locationAccess(context)
        val then = pending
        pending = null
        if (access == LocationAccess.None) { refused = true; return@rememberLauncherForActivityResult }
        refused = false
        change { s -> (then?.invoke(s) ?: s).copy(approximateOnly = access == LocationAccess.Approximate) }
    }
    fun withLocation(f: (LocationSettings) -> LocationSettings) {
        if (locationAccess(context) != LocationAccess.None) {
            access = locationAccess(context)
            change { f(it).copy(approximateOnly = access == LocationAccess.Approximate) }
        } else {
            pending = f
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        devices = pairedDevices(context)
        if (granted && settings.device != null) change { it.copy(stopOnDevice = true) }
    }
    // Permission may have been taken away in the phone's settings since: the switches follow.
    LaunchedEffect(Unit) {
        if (access == LocationAccess.None && settings.wantsLocation) change { it.copy(stopByPlace = false, savePath = false) }
        else if (settings.wantsLocation) change { it.copy(approximateOnly = access == LocationAccess.Approximate) }
        if (devices == null && settings.stopOnDevice) change { it.copy(stopOnDevice = false) }
    }

    LocationPage(
        LocationUi(settings, access, paired, devices, adding, addFailed, refused),
        LocationActions(
            setStopByPlace = { on -> if (on) withLocation { it.copy(stopByPlace = true) } else change { it.copy(stopByPlace = false) } },
            setSavePath = { on -> if (on) withLocation { it.copy(savePath = true) } else change { it.copy(savePath = false) } },
            change = ::change,
            addHere = { name ->
                adding = true
                addFailed = false
                takeOneFix(context) { point ->
                    adding = false
                    if (point == null) addFailed = true
                    else change { s ->
                        s.copy(places = s.places + SavedPlace("p${System.currentTimeMillis()}", name, point), stopOnArrive = true)
                    }
                }
            },
            removePlace = { id -> change { s -> s.copy(places = s.places.filter { it.id != id }).let { if (it.places.isEmpty()) it.copy(stopOnArrive = false) else it } } },
            askDevices = { askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        ),
        onBack,
    )
}

/** Takes one fix now (on the main thread's callback); null when location is off or none came. */
@SuppressLint("MissingPermission")
fun takeOneFix(context: Context, done: (GeoPoint?) -> Unit) {
    if (locationAccess(context) == LocationAccess.None) { done(null); return }
    val manager = context.getSystemService(LocationManager::class.java)
    if (manager == null || !manager.isLocationEnabled) { done(null); return }
    runCatching {
        manager.getCurrentLocation(bestProvider(manager), null, ContextCompat.getMainExecutor(context)) { location ->
            done(location?.let { GeoPoint(it.latitude, it.longitude) })
        }
    }.onFailure { done(null) }
}
