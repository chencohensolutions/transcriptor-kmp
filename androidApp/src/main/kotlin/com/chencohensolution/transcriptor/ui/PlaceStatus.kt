package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.location.MomentLocation
import com.chencohensolution.transcriptor.location.PlaceWatch
import com.chencohensolution.transcriptor.location.StopReason
import kotlinx.coroutines.delay

/** What the recording screen can do about stop by place. */
class PlaceActions(
    /** "Keep recording" on the countdown. */
    val keepRecording: () -> Unit = {},
    /** "Stop now" on the countdown. */
    val stopNow: () -> Unit = {},
    /** "Cancel for this recording". */
    val cancel: () -> Unit = {},
    /** "Set the place again here". */
    val here: () -> Unit = {},
    /** The recording screen's switches: Stop when leaving, Stop when back here, Path. */
    val set: (PlaceSwitch, on: Boolean) -> Unit = { _, _ -> },
)

/** The recording screen's location switches (owner's request, Nov 21, 2026). */
enum class PlaceSwitch { Leave, Back, Path }

/**
 * Stop by place and the path while recording, under the top bar (only when one of them is on for this moment): a
 * small line ("Stops when leaving, 250 m from here · Path") that opens Cancel / Set the place again here, and, while
 * the countdown runs, a banner with its seconds, Keep recording and Stop now.
 */
@Composable
fun PlaceStatusBar(status: MomentLocation.Status, elapsedMillis: Long, actions: PlaceActions) {
    val w = status.watch
    if (w is PlaceWatch.State.Counting) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(w) { while (true) { now = System.currentTimeMillis(); delay(500) } }
        val seconds = ((w.endsAtMillis - now) / 1000).coerceAtLeast(0)
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (w.reason) {
                        StopReason.Left -> stringResource(R.string.place_left)
                        StopReason.Arrived -> stringResource(R.string.place_arrived, w.place?.name ?: "")
                        StopReason.Device -> stringResource(R.string.place_device)
                        StopReason.Returned -> stringResource(R.string.place_returned)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.place_stops_in, seconds), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.keepRecording) { Text(stringResource(R.string.place_keep)) }
                    OutlinedButton(onClick = actions.stopNow) { Text(stringResource(R.string.place_stop_now)) }
                }
            }
        }
        return
    }
    val leave = status.leaveMeters
    val stop = when {
        w is PlaceWatch.State.NoFix && status.lastFixAt == null && elapsedMillis > 120_000 -> stringResource(R.string.place_status_no_fix)
        w is PlaceWatch.State.NoFix -> stringResource(R.string.place_status_finding)
        w is PlaceWatch.State.Watching -> listOfNotNull(
            leave?.let { stringResource(R.string.place_status_leaving, it) },
            stringResource(R.string.place_status_back).takeIf { status.returning },
        ).joinToString(" · ").ifEmpty { stringResource(R.string.place_status_on) }
        else -> null
    }
    val path = stringResource(R.string.place_status_path).takeIf { status.pathOn }
    val line = listOfNotNull(stop, path).joinToString(" · ").ifEmpty { return }
    var asking by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = stop != null) { asking = true }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LocationOn, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(stringResource(R.string.location_stop)) },
        text = { Text(stop ?: "") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (status.leaveMeters != null) TextButton(onClick = { asking = false; actions.here() }) { Text(stringResource(R.string.place_here)) }
                TextButton(onClick = { asking = false; actions.cancel() }) { Text(stringResource(R.string.place_cancel)) }
                TextButton(onClick = { asking = false }) { Text(stringResource(R.string.place_close)) }
            }
        },
    )
}
