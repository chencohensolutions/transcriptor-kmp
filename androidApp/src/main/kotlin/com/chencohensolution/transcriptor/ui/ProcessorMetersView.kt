package com.chencohensolution.transcriptor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.recording.Clock
import com.chencohensolution.transcriptor.recording.ProcessorMeters

// The processor meters on the lecture screen (Settings › Advanced, round of Nov 7, 2026: all three styles promoted).
// CPU is this app's share of all the phone's cores, GPU the whole phone's busy %, NPU only our own models' time on it
// (the phone hides its total). A meter the phone doesn't let the app read is left out.

/** The meters as a strip of three bars under the top bar (round's option a). */
@Composable
internal fun MetersBars(m: ProcessorMeters) {
    val shown = listOfNotNull(
        m.cpuPercent?.let { Triple(R.string.meters_cpu, it, 1f) },
        m.gpuPercent?.let { Triple(R.string.meters_gpu, it, 1f) },
        m.npuOursPercent?.let { Triple(R.string.meters_npu_ours, it, 1.3f) },
    )
    if (shown.isEmpty()) return
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            shown.forEach { (label, percent, weight) ->
                Column(Modifier.weight(weight)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("$percent%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                    MeterBar(percent, Modifier.fillMaxWidth().padding(top = 3.dp))
                }
            }
        }
        HorizontalDivider()
    }
}

/** The meters as the last minute in three small graphs under the top bar; a tap folds them into one line (option c). */
@Composable
internal fun MetersGraphs(m: ProcessorMeters, history: List<ProcessorMeters>) {
    var folded by rememberSaveable { mutableStateOf(false) }
    val shown = listOfNotNull(
        m.cpuPercent?.let { Triple(R.string.meters_cpu, it, history.map { h -> h.cpuPercent }) },
        m.gpuPercent?.let { Triple(R.string.meters_gpu, it, history.map { h -> h.gpuPercent }) },
        m.npuOursPercent?.let { Triple(R.string.meters_npu_ours, it, history.map { h -> h.npuOursPercent }) },
    )
    if (shown.isEmpty()) return
    Surface(onClick = { folded = !folded }, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            if (folded) {
                val labels = shown.map { foldedLabel(it.first) }
                Text(
                    shown.mapIndexed { i, (_, percent, _) -> "${labels[i]} $percent%" }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalTranscriptorColors.current.time,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    shown.forEach { (label, percent, line) -> Graph(stringResource(label), percent, line, Modifier.weight(1f)) }
                }
                Text(
                    stringResource(R.string.meters_last_minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalTranscriptorColors.current.time,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun foldedLabel(label: Int) = stringResource(
    when (label) {
        R.string.meters_cpu -> R.string.meters_short_cpu
        R.string.meters_gpu -> R.string.meters_short_gpu
        else -> R.string.meters_short_npu
    },
)

/** A small chip with three tiny bars, under the top bar's end; tapping it opens [MetersSheet] (option b). */
@Composable
internal fun MetersChip(m: ProcessorMeters, modifier: Modifier, onClick: () -> Unit) {
    val shown = listOfNotNull(
        m.cpuPercent?.let { R.string.meters_short_cpu to it },
        m.gpuPercent?.let { R.string.meters_short_gpu to it },
        m.npuOursPercent?.let { R.string.meters_short_npu to it },
    )
    if (shown.isEmpty()) return
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            shown.forEach { (label, percent) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 3.dp)) {
                    TinyColumn(percent)
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time)
                }
            }
        }
    }
}

/** The chip's sheet: each meter explained, the clocks and the NPU's temperature, as far as the phone lets the app read them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MetersSheet(m: ProcessorMeters, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.meters_title), style = MaterialTheme.typography.titleLarge)
            m.cpuPercent?.let { SheetMeter(stringResource(R.string.meters_cpu_long), it, stringResource(R.string.meters_cpu_note, m.cpuCoresBusy ?: 0f, m.cpuCores)) }
            m.gpuPercent?.let { SheetMeter(stringResource(R.string.meters_gpu_long), it, stringResource(R.string.meters_gpu_note)) }
            m.npuOursPercent?.let { SheetMeter(stringResource(R.string.meters_npu_long), it, stringResource(R.string.meters_npu_note)) }
            if (m.cpuClocks.isNotEmpty() || m.gpuClock != null || m.npuTemperatureC != null) {
                HorizontalDivider()
                Text(stringResource(R.string.meters_clocks), style = MaterialTheme.typography.titleSmall)
            }
            m.cpuClocks.forEach { group -> ClockRow(stringResource(R.string.meters_cores, group.first, group.last), group.clock, ghz = true) }
            m.gpuClock?.let { ClockRow(stringResource(R.string.meters_gpu_clock), it, ghz = false) }
            m.npuTemperatureC?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 30 °C cool, 80 °C hot.
                    Box(Modifier.size(8.dp).clip(CircleShape).background(meterColor(((t - 30f) * 2).toInt())))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.meters_npu_temperature), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.meters_celsius, t), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SheetMeter(label: String, percent: Int, note: String) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("$percent%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        MeterBar(percent, Modifier.fillMaxWidth().padding(vertical = 4.dp), height = 8.dp)
        Text(note, style = MaterialTheme.typography.bodySmall, color = LocalTranscriptorColors.current.time)
    }
}

@Composable
private fun ClockRow(label: String, clock: Clock, ghz: Boolean) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (ghz) stringResource(R.string.meters_ghz, clock.currentMhz / 1000f, clock.maxMhz / 1000f)
                else stringResource(R.string.meters_mhz, clock.currentMhz, clock.maxMhz),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        MeterBar(clock.percent, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** A meter's color: the theme's, amber from 70%, red from 90%. */
@Composable
internal fun meterColor(percent: Int): Color {
    val target = when {
        percent >= 90 -> MaterialTheme.colorScheme.error
        percent >= 70 -> Color(0xFFE09A2B)
        else -> MaterialTheme.colorScheme.primary
    }
    return animateColorAsState(target, tween(400), label = "meter color").value
}

/** A thin bar filled to [percent], gliding between readings. */
@Composable
internal fun MeterBar(percent: Int, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val fill by animateFloatAsState(percent / 100f, tween(450), label = "meter")
    val color = meterColor(percent)
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(height / 2)).background(color))
    }
}

/** A short upright bar, 18 dp tall when full. */
@Composable
private fun TinyColumn(percent: Int) {
    val fill by animateFloatAsState(percent / 100f, tween(450), label = "tiny")
    Box(Modifier.width(7.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxWidth().height(18.dp * fill.coerceAtLeast(0.06f)).background(meterColor(percent)))
    }
}

/** A label and figure over a 60 s graph filling from the start side, newest at the end. */
@Composable
private fun Graph(label: String, now: Int, history: List<Int?>, modifier: Modifier) {
    val color = meterColor(now)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time, maxLines = 1, modifier = Modifier.weight(1f))
            Text("$now%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(22.dp).padding(top = 2.dp)) {
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            if (history.size < 2) return@Canvas
            val step = size.width / (GRAPH_POINTS - 1)
            val first = (GRAPH_POINTS - history.size).coerceAtLeast(0)
            val points = history.takeLast(GRAPH_POINTS)
            fun x(i: Int) = if (rtl) size.width - (first + i) * step else (first + i) * step
            fun y(v: Int?) = size.height - (v ?: 0) / 100f * size.height
            val line = Path().apply { points.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            val area = Path().apply {
                addPath(line)
                lineTo(x(points.lastIndex), size.height)
                lineTo(x(0), size.height)
                close()
            }
            drawPath(area, color.copy(alpha = 0.18f), style = Fill)
            drawPath(line, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(color, 2.5.dp.toPx(), Offset(x(points.lastIndex), y(points.last())))
        }
    }
}

/** A minute of readings, one a second. */
private const val GRAPH_POINTS = 60
