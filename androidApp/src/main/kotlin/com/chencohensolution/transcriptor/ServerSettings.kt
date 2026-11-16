package com.chencohensolution.transcriptor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.shed.journal.PairingException
import com.chencohensolution.transcriptor.ui.PairingUi
import com.chencohensolution.transcriptor.ui.ServerActions
import com.chencohensolution.transcriptor.ui.ServerScreen
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
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { Recording.backup.refresh() }
            delay(1_000)
        }
    }
    // The server's list of recordings, once paired and each time the page opens.
    LaunchedEffect(state.paired != null) {
        if (state.paired != null) withContext(Dispatchers.IO) {
            runCatching { Recording.backup.syncServerList() }.onFailure { android.util.Log.w("ServerSettings", "the server's list", it) }
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
                    if (Recording.backup.waiting() > 0) Recording.scheduleUploads(context)
                }
            },
            backUpNow = { scope.launch(Dispatchers.IO) { Recording.backUp(context) } },
            unpair = {
                scope.launch(Dispatchers.IO) {
                    com.chencohensolution.shed.android.ShedUploads.cancel(context)
                    Recording.backup.unpair()
                }
            },
        ),
    )
}
