package com.chencohensolution.transcriptor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.shed.android.ShedSiblings
import com.chencohensolution.shed.android.SiblingApp
import com.chencohensolution.shed.journal.PairingException
import com.chencohensolution.transcriptor.ui.PairingUi
import com.chencohensolution.transcriptor.ui.ServerActions
import com.chencohensolution.transcriptor.ui.ServerScreen
import com.chencohensolution.transcriptor.ui.WifiUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Server backup, fed from [Recording.backup]: its state read every second while the page is open. */
@Composable
fun ServerSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Recording.backup.state.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf<PairingUi>(PairingUi.Idle) }
    // Other apps on the phone built with shed (journal): one of them may already be paired.
    val siblings = remember { ShedSiblings.find(context) }
    var asked by remember { mutableStateOf<SiblingApp?>(null) }
    val askSibling = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val from = asked ?: return@rememberLauncherForActivityResult
        val link = ShedSiblings.linkOf(result.data)
        if (link == null) {
            pairing = when (ShedSiblings.reasonOf(result.data)) {
                ShedSiblings.NOT_PAIRED -> PairingUi.Failed(context.getString(R.string.server_from_app_not_paired, from.label))
                ShedSiblings.FAILED -> PairingUi.Failed(context.getString(R.string.server_failed, result.data?.getStringExtra(ShedSiblings.EXTRA_MESSAGE) ?: ""))
                else -> PairingUi.Idle
            }
            return@rememberLauncherForActivityResult
        }
        pairing = PairingUi.Checking
        scope.launch {
            pairing = runCatching { withContext(Dispatchers.IO) { Recording.backup.pair(link, Recording.shed.defaultDeviceName) } }
                .fold({ PairingUi.Idle }, { PairingUi.Failed(context.getString(R.string.server_failed, it.message ?: it.toString())) })
            if (pairing == PairingUi.Idle) withContext(Dispatchers.IO) { Recording.backUp(context) }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { Recording.backup.refresh() }
            delay(1_000)
        }
    }
    // The server's list of recordings, once paired and each time the page opens.
    LaunchedEffect(state.paired != null) {
        if (state.paired != null) withContext(Dispatchers.IO) {
            Recording.refreshServerMoments()
            Recording.backup.refreshCache()
        }
    }
    val failed = { e: Throwable ->
        PairingUi.Failed(
            when (e) {
                is PairingException.NotALink -> context.getString(R.string.server_not_a_link)
                is PairingException.Rejected -> when (e.reason) {
                    "INVALID_CODE" -> context.getString(R.string.server_invalid_code)
                    "RATE_LIMITED" -> context.getString(R.string.server_rate_limited)
                    else -> context.getString(R.string.server_failed, e.reason)
                }
                is PairingException.Unreachable -> context.getString(R.string.server_unreachable, e.detail)
                else -> context.getString(R.string.server_failed, e.message ?: e.toString())
            },
        )
    }
    var locks by remember { mutableStateOf(Recording.locks()) }
    // The phone's call recordings folder, read only (Samsung keeps it out of the media store with a .nomedia).
    val pickCalls = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch(Dispatchers.IO) { runCatching { Recording.chooseCallsFolder(context, tree) } }
    }
    // The Wi‑Fi network the phone is on, for "Add this network" (its name needs location), and whether the background
    // work may read it too (location "all the time").
    fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var wifi by remember { mutableStateOf(WifiUi()) }
    LaunchedEffect(Unit) {
        while (true) {
            wifi = withContext(Dispatchers.IO) {
                WifiUi(
                    current = com.chencohensolution.shed.android.WifiName.current(context),
                    location = granted(android.Manifest.permission.ACCESS_FINE_LOCATION),
                    background = granted(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                )
            }
            delay(2_000)
        }
    }
    fun setNetworks(names: Set<String>) = scope.launch(Dispatchers.IO) {
        Recording.backup.setWifiNetworks(names)
        if (Recording.backup.waiting() > 0) Recording.scheduleUploads(context, rulesChanged = true)
    }
    // Asked for first, then the network is added once its name can be read.
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val name = com.chencohensolution.shed.android.WifiName.current(context)
        wifi = wifi.copy(current = name, location = granted(android.Manifest.permission.ACCESS_FINE_LOCATION))
        if (name != null) setNetworks(Recording.backup.wifiNetworks + name)
    }
    // Android 11 and later: opens the app's location page, where "Allow all the time" is.
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        wifi = wifi.copy(background = it)
    }
    ServerScreen(
        state, pairing, Recording.shed.defaultDeviceName,
        ServerActions(
            back = onBack,
            check = { text ->
                pairing = PairingUi.Checking
                scope.launch {
                    pairing = runCatching { withContext(Dispatchers.IO) { Recording.backup.preview(text) } }
                        .fold({ PairingUi.Preview(it.user, it.server.removePrefix("https://")) }, failed)
                }
            },
            pairFrom = { i ->
                siblings.getOrNull(i)?.let { sibling ->
                    asked = sibling
                    askSibling.launch(ShedSiblings.ask(sibling, "audio-logs"))
                }
            },
            pair = { text, name ->
                pairing = PairingUi.Checking
                scope.launch {
                    pairing = runCatching { withContext(Dispatchers.IO) { Recording.backup.pair(text, name) } }
                        .fold({ PairingUi.Idle }, failed)
                    if (pairing == PairingUi.Idle) withContext(Dispatchers.IO) { Recording.backUp(context) }
                }
            },
            setWifiOnly = { on ->
                scope.launch(Dispatchers.IO) {
                    Recording.backup.setWifiOnly(on)
                    // The work waiting is scheduled again with the new network rule.
                    if (Recording.backup.waiting() > 0) Recording.scheduleUploads(context, rulesChanged = true)
                }
            },
            setChargingOnly = { on ->
                scope.launch(Dispatchers.IO) {
                    Recording.backup.setChargingOnly(on)
                    if (Recording.backup.waiting() > 0) Recording.scheduleUploads(context, rulesChanged = true)
                }
            },
            addThisNetwork = {
                val name = com.chencohensolution.shed.android.WifiName.current(context)
                if (name != null) setNetworks(Recording.backup.wifiNetworks + name)
                else askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            removeNetwork = { name -> setNetworks(Recording.backup.wifiNetworks - name) },
            allowBackgroundLocation = { askBackground.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            backUpNow = { Recording.uploadNow(context) },
            onMeteredNetwork = { Recording.onMeteredNetwork(context) },
            setShowServer = { on -> scope.launch(Dispatchers.IO) { Recording.backup.setShowServer(on) } },
            setShown = { shown ->
                scope.launch(Dispatchers.IO) {
                    runCatching { Recording.backup.setShown(shown) }
                    Recording.refreshServerMoments()
                }
            },
            setLocks = { new ->
                locks = new
                scope.launch(Dispatchers.IO) { Recording.setLocks(new) }
            },
            setCacheLimit = { bytes -> scope.launch(Dispatchers.IO) { Recording.backup.setCacheLimit(bytes) } },
            planFreeUp = { rule -> withContext(Dispatchers.IO) { Recording.freeUpPlan(rule) } },
            freeUp = { ids, progress -> withContext(Dispatchers.IO) { Recording.freeUp(ids, progress) } },
            chooseCallsFolder = { pickCalls.launch(null) },
            stopCalls = { scope.launch(Dispatchers.IO) { Recording.stopCalls() } },
            unpair = {
                scope.launch(Dispatchers.IO) {
                    com.chencohensolution.shed.android.ShedUploads.cancel(context)
                    Recording.backup.unpair()
                }
            },
        ),
        siblings.map { it.label },
        locks,
        wifi,
    )
}
