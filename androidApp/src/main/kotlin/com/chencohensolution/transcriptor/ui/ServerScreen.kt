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
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import com.chencohensolution.transcriptor.backup.FreeUpResult
import com.chencohensolution.transcriptor.backup.FreeUpPlan
import com.chencohensolution.transcriptor.backup.FreeUpRule
import com.chencohensolution.shed.journal.SoundRecordingsShown
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
    /** Turns calls or voice messages on or off for Audio Logs on the server. */
    val setShown: (SoundRecordingsShown) -> Unit = {},
    /** Puts calls or voice messages behind the phone's lock, or takes them out. */
    val setLocks: (com.chencohensolution.transcriptor.notebook.Locks) -> Unit = {},
    /** Sets the most shed's cache keeps on the phone. */
    val setCacheLimit: (Long) -> Unit = {},
    /** What Free up space would remove with a rule (files: off the main thread). */
    val planFreeUp: suspend (FreeUpRule) -> FreeUpPlan = { FreeUpPlan(emptyList(), 0) },
    /** Removes the audio of these moments from the phone, once the server checked it holds it. */
    val freeUp: suspend (ids: List<String>, progress: (Int, Int) -> Unit) -> FreeUpResult = { _, _ -> FreeUpResult(0, 0) },
    /** Opens the system's folder picker for the phone's call recordings folder. */
    val chooseCallsFolder: () -> Unit = {},
    /** Stops backing up calls (what was sent stays on the server). */
    val stopCalls: () -> Unit = {},
)

/**
 * Settings › Server backup (docs/screens/server.md): off until the user pairs Audio Logs with their journal server by
 * pasting the enrollment link; then how the backup stands, Wi‑Fi only, back up now, the server's recordings that aren't
 * moments here, and unpairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    state: ServerState, pairing: PairingUi, defaultDeviceName: String, actions: ServerActions, siblings: List<String> = emptyList(),
    locks: com.chencohensolution.transcriptor.notebook.Locks = com.chencohensolution.transcriptor.notebook.Locks(),
) {
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
            if (paired == null) NotPaired(pairing, defaultDeviceName, siblings, actions) else Paired(state, actions, locks)
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
private fun Paired(state: ServerState, actions: ServerActions, locks: com.chencohensolution.transcriptor.notebook.Locks) {
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
    state.shown?.let { shown ->
        HorizontalDivider()
        Text(stringResource(R.string.server_show), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        ShowSwitch(R.string.server_show_calls, R.string.server_show_calls_note, shown.calls) { actions.setShown(shown.copy(calls = it)) }
        ShowSwitch(R.string.server_show_voice, R.string.server_show_voice_note, shown.voiceMessages) { actions.setShown(shown.copy(voiceMessages = it)) }
        Text(stringResource(R.string.server_show_private_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    CallsSection(state, actions)
    HorizontalDivider()
    // Calls and voice messages behind the phone's lock, in the moments list and search (owner's request, Nov 16, 2026).
    Text(stringResource(R.string.server_lock), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    ShowSwitch(R.string.server_lock_calls, R.string.server_lock_calls_note, locks.calls) { actions.setLocks(locks.copy(calls = it)) }
    ShowSwitch(R.string.server_lock_voice, R.string.server_lock_voice_note, locks.voiceMessages) { actions.setLocks(locks.copy(voiceMessages = it)) }
    Text(stringResource(R.string.server_lock_labels_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    FreeUpSection(state, actions)
    HorizontalDivider()
    Text(stringResource(R.string.server_cache), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        stringResource(R.string.server_cache_note, modelsSize(state.cacheBytes), modelsSize(state.cacheLimitBytes)),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CACHE_LIMITS.forEach { gb ->
            val bytes = gb.toLong() shl 30
            FilterChip(selected = state.cacheLimitBytes == bytes, onClick = { actions.setCacheLimit(bytes) }, label = { Text(stringResource(R.string.server_cache_gb, gb)) })
        }
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

/** Backing up the phone's call recordings: the folder picked, how many were handed over, or a button to pick one. */
@Composable
private fun CallsSection(state: ServerState, actions: ServerActions) {
    Text(stringResource(R.string.server_calls), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    val folder = state.callsFolder
    if (folder == null) {
        Text(stringResource(R.string.server_calls_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = actions.chooseCallsFolder) { Text(stringResource(R.string.server_calls_choose)) }
        return
    }
    Text(
        when {
            state.callsWaiting > 0 -> stringResource(R.string.server_calls_waiting, isolated(folder), state.callsWaiting)
            else -> stringResource(R.string.server_calls_on, isolated(folder), state.callsHanded)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(stringResource(R.string.server_calls_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.chooseCallsFolder) { Text(stringResource(R.string.server_calls_change)) }
        TextButton(onClick = actions.stopCalls) { Text(stringResource(R.string.server_calls_stop)) }
    }
}

/** One switch of what Audio Logs shows from the server, with what it means. */
@Composable
private fun ShowSwitch(title: Int, note: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

private val CACHE_LIMITS = listOf(2, 10, 20, 50)

/** The rules Free up space offers, as in the journal app: everything backed up, older than, or all but the newest. */
private val FREE_UP_RULES = listOf(FreeUpRule.Everything, FreeUpRule.OlderThan(3), FreeUpRule.OlderThan(12), FreeUpRule.KeepNewest(10L shl 30))

@Composable
private fun ruleName(rule: FreeUpRule): String = when (rule) {
    FreeUpRule.Everything -> stringResource(R.string.server_free_everything)
    is FreeUpRule.OlderThan -> pluralStringResource(R.plurals.server_free_older, rule.months, rule.months)
    is FreeUpRule.KeepNewest -> stringResource(R.string.server_free_keep_newest, (rule.bytes shr 30).toInt())
}

/**
 * Free up space (as in the journal app, manual only): a rule, what it would remove ("12 moments · 3.2 GB"), and one
 * button. Only the audio goes, once the server checked it holds it; transcripts, notes and labels stay, and opening a
 * moment brings its audio back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FreeUpSection(state: ServerState, actions: ServerActions) {
    var rule by remember { mutableStateOf<FreeUpRule>(FreeUpRule.Everything) }
    var plan by remember { mutableStateOf<FreeUpPlan?>(null) }
    var running by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<FreeUpResult?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(rule, state.backedUp, result) { plan = actions.planFreeUp(rule) }
    Text(stringResource(R.string.server_free), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.server_free_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FREE_UP_RULES.forEach { r -> FilterChip(selected = rule == r, onClick = { rule = r; result = null }, label = { Text(ruleName(r)) }) }
    }
    val p = plan
    Text(
        when {
            p == null -> stringResource(R.string.server_checking)
            p.ids.isEmpty() -> stringResource(R.string.server_free_nothing)
            else -> pluralStringResource(R.plurals.server_free_plan, p.ids.size, p.ids.size, modelsSize(p.bytes))
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    running?.let { (done, of) ->
        LinearProgressIndicator(progress = { if (of == 0) 0f else done.toFloat() / of }, modifier = Modifier.fillMaxWidth())
    }
    result?.let { r ->
        Text(
            r.stopped.let { stopped ->
                if (stopped == null) stringResource(R.string.server_free_done, r.freed, modelsSize(r.bytes))
                else stringResource(R.string.server_free_stopped, r.freed, stopped)
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    FilledTonalButton(
        enabled = p != null && p.ids.isNotEmpty() && running == null,
        onClick = {
            val ids = p?.ids ?: return@FilledTonalButton
            running = 0 to ids.size
            scope.launch {
                result = actions.freeUp(ids) { done, of -> running = done to of }
                running = null
            }
        },
    ) { Text(stringResource(R.string.server_free_button, modelsSize(p?.bytes ?: 0))) }
}
