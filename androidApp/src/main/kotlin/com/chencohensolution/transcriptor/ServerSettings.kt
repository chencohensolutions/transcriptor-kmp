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
                    if (Recording.backup.waiting() > 0) Recording.scheduleUploads(context)
                }
            },
            backUpNow = { scope.launch(Dispatchers.IO) { Recording.backUp(context); Recording.refreshServerMoments() } },
            setShown = { shown ->
                scope.launch(Dispatchers.IO) {
                    runCatching { Recording.backup.setShown(shown) }
                    Recording.refreshServerMoments()
                }
            },
            setCacheLimit = { bytes -> scope.launch(Dispatchers.IO) { Recording.backup.setCacheLimit(bytes) } },
            planFreeUp = { rule -> withContext(Dispatchers.IO) { Recording.freeUpPlan(rule) } },
            freeUp = { ids, progress -> withContext(Dispatchers.IO) { Recording.freeUp(ids, progress) } },
            unpair = {
                scope.launch(Dispatchers.IO) {
                    com.chencohensolution.shed.android.ShedUploads.cancel(context)
                    Recording.backup.unpair()
                }
            },
        ),
        siblings.map { it.label },
    )
}
