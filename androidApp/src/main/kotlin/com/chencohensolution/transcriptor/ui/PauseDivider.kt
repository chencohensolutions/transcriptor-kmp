package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.notebook.LiveOff
import com.chencohensolution.transcriptor.notebook.Pause
import java.text.DateFormat
import java.util.Date

/**
 * Where the recording was paused, between the lines: "Paused 12 min · resumed 14:32", or "Paused at 14:20" while it
 * still is (or when it was stopped paused). The audio has no gap there; this is when the next part was recorded.
 */
@Composable
fun PauseDivider(pause: Pause, modifier: Modifier = Modifier) {
    val color = LocalTranscriptorColors.current.time
    val resumed = pause.resumedAtMillis
    val text = if (resumed == null) stringResource(R.string.pause_divider_open, clockTime(pause.pausedAtMillis))
        else stringResource(R.string.pause_divider, pauseLength(resumed - pause.pausedAtMillis), clockTime(resumed))
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.4f))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.4f))
    }
}

/**
 * Where live transcription was switched off while recording: "Live transcript off 12 min · on again 14:32", "off since
 * 14:20" while it still is, or "off from 14:20 to the end". Those lines come from the pass after the moment.
 */
@Composable
fun TranscriptOffDivider(off: LiveOff, modifier: Modifier = Modifier) {
    val color = LocalTranscriptorColors.current.time
    val on = off.onAtMillis
    val text = when {
        on != null -> stringResource(R.string.transcript_off_divider, pauseLength(on - off.offAtMillis), clockTime(on))
        off.toMillis == null -> stringResource(R.string.transcript_off_open, clockTime(off.offAtMillis))
        else -> stringResource(R.string.transcript_off_to_end, clockTime(off.offAtMillis))
    }
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.4f))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.4f))
    }
}

/** A clock time, with its date when it isn't today ("14:32", "Nov 14, 14:32"). */
fun clockTime(epochMillis: Long): String {
    val day = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))
    val date = day.format(Date(epochMillis))
    return if (date == day.format(Date())) time else "$date, $time"
}

/** "40 s", "12 min", "1 h 5 min". */
@Composable
fun pauseLength(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> stringResource(R.string.pause_seconds, seconds.toInt())
        seconds < 3600 -> stringResource(R.string.pause_minutes, (seconds / 60).toInt())
        else -> stringResource(R.string.pause_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
    }
}
