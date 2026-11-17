package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.location.KeepUntil
import com.chencohensolution.transcriptor.location.LocationSettings
import com.chencohensolution.transcriptor.location.PathPrecision
import com.chencohensolution.transcriptor.location.StopDevice

/** What the phone lets the app know of its location. */
enum class LocationAccess { None, Approximate, Precise }

/** Settings › Advanced › Location's state. */
data class LocationUi(
    val settings: LocationSettings = LocationSettings(),
    val access: LocationAccess = LocationAccess.None,
    /** Paired with a server: Sync location is offered. */
    val paired: Boolean = false,
    /** The paired Bluetooth devices; null until the Bluetooth permission is given. */
    val devices: List<StopDevice>? = null,
    /** Taking a fix for "Add the current place". */
    val adding: Boolean = false,
    /** The last "Add the current place" got no fix. */
    val addFailed: Boolean = false,
    /** The last permission request was refused. */
    val refused: Boolean = false,
)

class LocationActions(
    /** Turns stop by place on (asking for location first) or off. */
    val setStopByPlace: (Boolean) -> Unit = {},
    /** Turns path saving on (asking for location first) or off. */
    val setSavePath: (Boolean) -> Unit = {},
    val change: ((LocationSettings) -> LocationSettings) -> Unit = {},
    /** Saves where the phone is now as [name]. */
    val addHere: (name: String) -> Unit = {},
    val removePlace: (id: String) -> Unit = {},
    /** Asks for the Bluetooth permission so the paired devices can be listed. */
    val askDevices: () -> Unit = {},
)

/** The line under Settings › Advanced › Location: what is on, or that it is off. */
@Composable
fun locationLine(s: LocationSettings): String = when {
    s.stopByPlace && s.savePath -> stringResource(R.string.location_line_both)
    s.stopByPlace -> stringResource(R.string.location_line_stop)
    s.savePath -> stringResource(R.string.location_line_path)
    else -> stringResource(R.string.location_line_off)
}

/**
 * Settings › Advanced › Location (owner's request, Nov 17, 2026): stop by place (leaving, arriving at a saved place,
 * the car's Bluetooth) and the moment's path (precision, after Stop, sync with the server). Nothing shows elsewhere
 * in the app, and no permission is asked for, while both main switches are off.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LocationPage(ui: LocationUi, actions: LocationActions, onBack: () -> Unit) {
    val s = ui.settings
    var naming by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review_back)) } },
                title = { Text(stringResource(R.string.location_title)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            PageNote(stringResource(R.string.location_privacy))
            if (ui.refused) PageNote(stringResource(R.string.location_refused))
            if (ui.access == LocationAccess.Approximate && s.wantsLocation) PageNote(stringResource(R.string.location_approximate))

            // Stop by place
            SwitchRow(stringResource(R.string.location_stop), stringResource(R.string.location_stop_note), s.stopByPlace, actions.setStopByPlace)
            if (s.stopByPlace) {
                SwitchRow(stringResource(R.string.location_stop_leaving), stringResource(R.string.location_stop_leaving_note), s.stopWhenLeaving) { on ->
                    actions.change { it.copy(stopWhenLeaving = on) }
                }
                if (s.stopWhenLeaving) {
                    Text(stringResource(R.string.location_stop_distance), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocationSettings.STOP_METERS.forEach { m ->
                            val allowed = ui.access != LocationAccess.Approximate || m == LocationSettings.STOP_METERS.last()
                            FilterChip(
                                selected = s.leaveMeters == m, enabled = allowed,
                                onClick = { actions.change { it.copy(stopMeters = m) } },
                                label = { Text(stringResource(R.string.location_meters, m)) },
                            )
                        }
                    }
                }
                SwitchRow(
                    stringResource(R.string.location_stop_arrive),
                    if (s.places.isEmpty()) stringResource(R.string.location_stop_arrive_none) else stringResource(R.string.location_stop_arrive_note),
                    s.stopOnArrive && s.places.isNotEmpty(),
                ) { on -> if (s.places.isEmpty()) naming = true else actions.change { it.copy(stopOnArrive = on) } }
                SectionTitle(stringResource(R.string.location_places))
                s.places.forEach { place ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(place.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { actions.removePlace(place.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.location_place_remove)) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !ui.adding) { naming = true }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ui.adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(
                        stringResource(if (ui.adding) R.string.location_place_finding else R.string.location_place_add),
                        Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (ui.addFailed) PageNote(stringResource(R.string.location_place_failed))
                SwitchRow(
                    stringResource(R.string.location_stop_device),
                    s.device?.name ?: stringResource(R.string.location_stop_device_note),
                    s.stopOnDevice && s.device != null,
                ) { on ->
                    if (!on) actions.change { it.copy(stopOnDevice = false) }
                    else if (ui.devices == null) actions.askDevices()
                    else actions.change { it.copy(stopOnDevice = true) }
                }
                if (s.stopOnDevice || (ui.devices != null && s.device == null)) DevicePicker(ui, actions)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            // The path
            SwitchRow(stringResource(R.string.location_path), stringResource(R.string.location_path_note), s.savePath, actions.setSavePath)
            if (s.savePath) {
                Column(Modifier.selectableGroup()) {
                    Text(stringResource(R.string.location_precision), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                    listOf(
                        PathPrecision.Passive to (R.string.location_precision_passive to R.string.location_precision_passive_note),
                        PathPrecision.Economical to (R.string.location_precision_economical to R.string.location_precision_economical_note),
                        PathPrecision.WhileDriving to (R.string.location_precision_driving to R.string.location_precision_driving_note),
                        PathPrecision.Precise to (R.string.location_precision_precise to R.string.location_precision_precise_note),
                    ).forEach { (option, labels) ->
                        val allowed = ui.access != LocationAccess.Approximate || option == PathPrecision.Passive || option == PathPrecision.Economical
                        Radio(s.pathPrecision == option, stringResource(labels.first), stringResource(labels.second), allowed) {
                            actions.change { it.copy(precision = option.key) }
                        }
                    }
                }
                SwitchRow(stringResource(R.string.location_keep), stringResource(R.string.location_keep_note), s.keepAfterStop) { on ->
                    actions.change { it.copy(keepAfterStop = on) }
                }
                if (s.keepAfterStop) {
                    Text(stringResource(R.string.location_keep_until), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            KeepUntil.SavedPlace to R.string.location_keep_place,
                            KeepUntil.Minutes30 to R.string.location_keep_30m,
                            KeepUntil.Hour1 to R.string.location_keep_1h,
                            KeepUntil.Hours2 to R.string.location_keep_2h,
                        ).forEach { (option, label) ->
                            FilterChip(selected = s.keep == option, onClick = { actions.change { it.copy(keepUntil = option.key) } }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                if (ui.paired) {
                    SwitchRow(stringResource(R.string.location_sync), stringResource(R.string.location_sync_note), s.syncLocation) { on ->
                        actions.change { it.copy(syncLocation = on) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (naming) PlaceNameDialog(onDismiss = { naming = false }) { name -> naming = false; actions.addHere(name) }
}

@Composable
private fun DevicePicker(ui: LocationUi, actions: LocationActions) {
    val devices = ui.devices ?: return
    Column(Modifier.selectableGroup().padding(bottom = 8.dp)) {
        if (devices.isEmpty()) PageNote(stringResource(R.string.location_device_none))
        devices.forEach { d ->
            Radio(ui.settings.device?.address == d.address, d.name, null, true) {
                actions.change { it.copy(device = d, stopOnDevice = true) }
            }
        }
    }
}

@Composable
private fun Radio(selected: Boolean, title: String, note: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Names the place the phone is at now ("Home"), before its fix is taken. */
@Composable
private fun PlaceNameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_place_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.location_place_add_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.location_place_name)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.location_place_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
