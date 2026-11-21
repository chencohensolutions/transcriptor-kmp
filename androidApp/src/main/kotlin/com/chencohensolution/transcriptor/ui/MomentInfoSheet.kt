package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.recording.PowerReport
import com.chencohensolution.transcriptor.review.MomentInfo
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A moment's Info (its menu › Info): when and how long, the audio (format, encoder, size, microphone), the models,
 * and what recording it took (power, energy per minute, battery, temperature, the app's CPU, the screen), with what
 * encoding it afterwards took for "WAV, then encode". For comparing formats and microphones on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentInfoSheet(info: MomentInfo, seek: (Long) -> Unit = {}, onDismiss: () -> Unit) {
    val l = info.lecture
    val r = info.recording
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))

            Section(stringResource(R.string.info_recording))
            Item(stringResource(R.string.info_started), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(l.startedAtMillis)))
            Item(stringResource(R.string.info_length), formatClock(l.durationMillis))
            Item(stringResource(R.string.info_language), Locale.forLanguageTag(l.language).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase() })
            val audio = r?.audio
            // The file now, and how it was recorded when that was different ("WAV, then AAC" encoded it afterwards).
            val codec = l.audioFile.substringAfterLast('.').lowercase()
            val recordedAs = audio?.codec?.lowercase()
            if (recordedAs == null || recordedAs == codec) {
                Item(stringResource(R.string.info_audio), listOfNotNull(codec.uppercase(), audio?.details?.takeIf { it.isNotBlank() }).joinToString(" · "))
            } else {
                Item(stringResource(R.string.info_audio), codec.uppercase())
                Item(stringResource(R.string.info_recorded_as), listOfNotNull(recordedAs.uppercase(), audio.details.takeIf { it.isNotBlank() }).joinToString(" · "))
            }
            audio?.encoder?.let { Item(stringResource(R.string.info_encoder), "$it · " + stringResource(if (audio.hardware) R.string.info_hardware else R.string.info_software)) }
            info.audioBytes?.let { Item(stringResource(R.string.info_size), stringResource(R.string.info_megabytes, it / 1_048_576.0)) }
            if (r != null) Item(stringResource(R.string.info_mic), audio?.input?.let(::isolated) ?: stringResource(R.string.settings_mic_phone))
            r?.models?.forEach { m -> Item(m.role.replaceFirstChar { it.titlecase() }, "${isolated(m.name)} · ${m.runsOn}") }
            // Where live transcription was off: the audio's stretch, and the clock times.
            l.liveOff.forEachIndexed { i, o ->
                val on = o.onAtMillis
                Item(
                    if (i == 0) stringResource(R.string.info_live_off) else "",
                    "${formatClock(o.fromMillis)}–${formatClock(o.toMillis ?: l.durationMillis)} · " +
                        if (on == null) stringResource(R.string.info_pause_open, clockTime(o.offAtMillis))
                        else stringResource(R.string.info_pause_row, clockTime(o.offAtMillis), clockTime(on), pauseLength(on - o.offAtMillis)),
                )
            }
            // Where it was paused: the clock times, with the audio's position (it has no gap there).
            l.pauses.forEachIndexed { i, p ->
                val resumed = p.resumedAtMillis
                Item(
                    if (i == 0) stringResource(R.string.info_pauses) else "",
                    "${formatClock(p.atMillis)} · " + if (resumed == null) stringResource(R.string.info_pause_open, clockTime(p.pausedAtMillis))
                        else stringResource(R.string.info_pause_row, clockTime(p.pausedAtMillis), clockTime(resumed), pauseLength(resumed - p.pausedAtMillis)),
                )
            }

            // Stop by place and the path (Settings › Advanced › Location).
            l.placeStop?.let { s ->
                Item(
                    stringResource(R.string.info_place_stop),
                    when (s.reason) {
                        "arrived" -> stringResource(R.string.info_place_stop_arrived, s.placeName ?: "")
                        "device" -> stringResource(R.string.info_place_stop_device)
                        "returned" -> stringResource(R.string.info_place_stop_returned)
                        else -> stringResource(R.string.info_place_stop_left)
                    } + " · " + formatClock(s.atMillis),
                )
            }
            info.path?.takeIf { !it.isEmpty }?.let { path ->
                Section(stringResource(R.string.info_path))
                Item(stringResource(R.string.info_path_distance), stringResource(R.string.info_path_km, path.distanceMeters() / 1000))
                path.topSpeedMps()?.let { Item(stringResource(R.string.info_path_top_speed), stringResource(R.string.info_path_kmh, it * 3.6f)) }
                Item(stringResource(R.string.info_path_points), path.points.size.toString())
                path.audioEndMillis?.takeIf { end -> path.points.any { it.atMillis > end } }?.let {
                    Note(stringResource(R.string.info_path_after))
                }
                // The map right here (fixed; a tap opens it full screen, where it pans, zooms and plays from the path).
                var onMap by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                PathMapView(
                    path, interactive = false, seek = seek, onTap = { onMap = true },
                    modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().height(220.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
                Note(stringResource(R.string.info_path_map_tap))
                if (onMap) PathMapDialog(path, seek) { onMap = false }
                SpeedGraph(path, seek, stringResource(R.string.info_path_speed), Modifier.padding(vertical = 8.dp))
            }

            Section(stringResource(R.string.info_power_recording))
            if (r == null) Note(stringResource(R.string.info_not_kept)) else PowerRows(r)

            info.encoding?.let { e ->
                Section(stringResource(R.string.info_power_encoding))
                Item(stringResource(R.string.info_length), formatClock(e.durationMillis))
                e.audio?.encoder?.let { Item(stringResource(R.string.info_encoder), "$it · " + stringResource(if (e.audio?.hardware == true) R.string.info_hardware else R.string.info_software)) }
                PowerRows(e)
            }
            Note(stringResource(R.string.info_whole_phone))
        }
    }
}

@Composable
private fun PowerRows(p: PowerReport) {
    val mw = p.averageMilliwatts
    if (p.charging || mw == null) Note(stringResource(R.string.info_charging))
    else {
        Item(stringResource(R.string.info_average), stringResource(R.string.info_watts, mw / 1000))
        Item(stringResource(R.string.info_energy), stringResource(R.string.info_energy_value, p.totalMilliwattHours ?: 0.0, p.milliwattHoursPerMinute ?: 0.0))
    }
    val start = p.batteryPercentStart
    val end = p.batteryPercentEnd
    if (start != null && end != null) Item(
        stringResource(R.string.info_battery),
        stringResource(R.string.info_battery_value, start, end) + (p.batteryPercentPerHour?.let { " · " + stringResource(R.string.info_per_hour, it) } ?: ""),
    )
    val t0 = p.temperatureStart
    val t1 = p.temperatureEnd
    if (t0 != null && t1 != null) Item(stringResource(R.string.info_temperature), stringResource(R.string.info_temperature_value, t0, t1, (if (t1 >= t0) "+" else "") + String.format(Locale.getDefault(), "%.1f", t1 - t0)))
    p.cpuSecondsPerMinute?.let { Item(stringResource(R.string.info_cpu), stringResource(R.string.info_cpu_value, it, it / 60 * 100)) }
    p.screenOnShare?.let { Item(stringResource(R.string.info_screen), stringResource(R.string.info_percent, (it * 100).toInt())) }
    Item(stringResource(R.string.info_samples), p.samples.toString())
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Item(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        // Figures and units ("1.42 W", "33.9 → 33.9 °C") read left to right in a Hebrew app too; a value with Hebrew
        // words in it is laid out right to left, its figures isolated in the strings.
        val hebrew = value.any { it in '\u0590'..'\u05FF' }
        Text(
            value, style = MaterialTheme.typography.bodyMedium.copy(textDirection = if (hebrew) TextDirection.Rtl else TextDirection.Ltr),
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f),
            // The layout's end edge, whatever the value's own direction.
            textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Left else TextAlign.Right,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
}
