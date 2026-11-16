package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import com.chencohensolution.shed.RunResult
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.backup.ServerState

/** Pairing as the Server page shows it, between the user's taps. */
sealed interface PairingUi {
    data object Idle : PairingUi
    data object Checking : PairingUi

    /** The link's code is for [user] on [server]. */
    data class Preview(val user: String, val server: String) : PairingUi

    /** Why it failed, in the user's words already. */
    data class Failed(val message: String) : PairingUi
}

class ServerActions(
    val back: () -> Unit = {},
    /** Asks the server who the link in the text is for, without using its code. */
    val check: (text: String) -> Unit = {},
    val pair: (text: String, deviceName: String) -> Unit = { _, _ -> },
    /** Asks another app on the phone that uses shed (by its place in the list) for its pairing. */
    val pairFrom: (Int) -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val backUpNow: () -> Unit = {},
    val unpair: () -> Unit = {},
)

/**
 * Settings › Server backup (docs/screens/server.md): off until the user pairs Audio Logs with their journal server by
 * pasting the enrollment link; then how the backup stands, Wi‑Fi only, back up now, the server's recordings that aren't
 * moments here, and unpairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(state: ServerState, pairing: PairingUi, defaultDeviceName: String, actions: ServerActions, siblings: List<String> = emptyList()) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.server_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val paired = state.paired
            if (paired == null) NotPaired(pairing, defaultDeviceName, siblings, actions) else Paired(state, actions)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NotPaired(pairing: PairingUi, defaultDeviceName: String, siblings: List<String>, actions: ServerActions) {
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(defaultDeviceName) }
    Text(stringResource(R.string.server_intro), style = MaterialTheme.typography.bodyMedium)
    // One enrollment per phone: an app already paired (journal) shares its server, with no new code.
    if (siblings.isNotEmpty()) {
        siblings.forEachIndexed { i, label ->
            Button(onClick = { actions.pairFrom(i) }, enabled = pairing != PairingUi.Checking) {
                Text(stringResource(R.string.server_from_app, isolated(label)))
            }
        }
        Text(stringResource(R.string.server_from_app_note, isolated(siblings.joinToString())), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
    }
    Text(stringResource(R.string.server_how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // The link and the name are left to right whatever the phone's language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.server_link)) }, minLines = 2)
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.server_device)) }, singleLine = true)
    when (pairing) {
        PairingUi.Checking -> {
            Text(stringResource(R.string.server_checking), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is PairingUi.Preview -> Text(stringResource(R.string.server_preview, isolated(pairing.user), isolated(pairing.server)), style = MaterialTheme.typography.bodySmall)
        is PairingUi.Failed -> Text(pairing.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        PairingUi.Idle -> Unit
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val busy = pairing == PairingUi.Checking
        OutlinedButton(onClick = { actions.check(link) }, enabled = !busy && link.isNotBlank()) { Text(stringResource(R.string.server_check)) }
        Button(onClick = { actions.pair(link, name.trim()) }, enabled = !busy && link.isNotBlank() && name.isNotBlank()) { Text(stringResource(R.string.server_pair)) }
    }
}

@Composable
private fun Paired(state: ServerState, actions: ServerActions) {
    val paired = state.paired ?: return
    var unpairing by rememberSaveable { mutableStateOf(false) }
    Text(
        stringResource(R.string.server_paired, isolated(paired.server.removePrefix("https://")), isolated(paired.user), isolated(paired.deviceName)),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        when {
            state.waiting > 0 -> stringResource(R.string.server_waiting, state.waiting, modelsSize(state.bytesLeft), state.backedUp)
            state.backedUp > 0 -> stringResource(R.string.server_all_backed_up, state.backedUp)
            else -> stringResource(R.string.server_none_yet)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    when (state.lastRun) {
        is RunResult.Offline -> if (state.waiting > 0) Text(stringResource(R.string.server_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RunResult.Unauthorized -> Text(stringResource(R.string.server_unauthorized), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
    state.refused.forEach { (moment, reason) ->
        Text(stringResource(R.string.server_refused, isolated(moment), reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    FilledTonalButton(onClick = actions.backUpNow) { Text(stringResource(R.string.server_back_up_now)) }
    Row(
        Modifier.fillMaxWidth().toggleable(value = state.wifiOnly, role = Role.Switch, onValueChange = actions.setWifiOnly).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.server_wifi), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (state.wifiOnly) R.string.server_wifi_on else R.string.server_wifi_off),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.wifiOnly, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
    HorizontalDivider()
    Text(stringResource(R.string.server_on_server), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (state.onServerOnly.isEmpty()) {
        Text(stringResource(R.string.server_on_server_none), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(stringResource(R.string.server_on_server_count, state.onServerOnly.size), style = MaterialTheme.typography.bodySmall)
        state.onServerOnly.sortedByDescending { it.about.takenAt ?: it.storedAt ?: "" }.take(20).forEach { file ->
            val line = listOfNotNull(
                file.about.takenAt?.take(16)?.replace('T', ' '),
                file.about.durationMillis?.let(::formatClock),
                file.about.fileName,
            ).joinToString(" · ")
            Text(isolated(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.server_on_server_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    TextButton(onClick = { unpairing = true }) { Text(stringResource(R.string.server_unpair), color = MaterialTheme.colorScheme.error) }
    if (unpairing) AlertDialog(
        onDismissRequest = { unpairing = false },
        title = { Text(stringResource(R.string.server_unpair_title)) },
        text = { Text(stringResource(R.string.server_unpair_note)) },
        confirmButton = { TextButton(onClick = { unpairing = false; actions.unpair() }) { Text(stringResource(R.string.server_unpair)) } },
        dismissButton = { TextButton(onClick = { unpairing = false }) { Text(stringResource(R.string.server_cancel)) } },
    )
}
