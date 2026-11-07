package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.recording.ProcessorMeters

// Option c: the last minute as three small graphs under the top bar, so the rhythm shows: with large-v3 in ~30 s
// windows the NPU rises for a window and rests between them, and a CPU that stays high is visible at a glance. Tap the
// strip to fold it into one line of figures. Fakes: the meters (LectureMetersLab) and the recording (LectureGrowingLab).
internal val lectureOptionC = LabOption("c", "Meters: the last minute as small graphs", "CPU · GPU · NPU (our use) over 60 s; tap to fold into one line") {
    val (state, actions) = rememberGrowingLectureLab()
    val meters = rememberFakeMeters(state.paused)
    var folded by remember { mutableStateOf(false) }
    Box {
        LectureScreen(state.copy(meters = meters.now), actions)
        UnderLectureTopBar { HistoryStrip(meters, folded) { folded = !folded } }
    }
}

@Composable
private fun HistoryStrip(meters: LabMeters, folded: Boolean, onClick: () -> Unit) {
    val m = meters.now
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            if (folded) {
                Text(
                    stringResource(R.string.lecture_c_folded, percent(m.cpuPercent), percent(m.gpuPercent), percent(m.npuOursPercent)),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalTranscriptorColors.current.time,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Graph(stringResource(R.string.lecture_c_cpu), m.cpuPercent, meters.history.map { it.cpuPercent }, Modifier.weight(1f))
                    Graph(stringResource(R.string.lecture_c_gpu), m.gpuPercent, meters.history.map { it.gpuPercent }, Modifier.weight(1f))
                    Graph(stringResource(R.string.lecture_c_npu_ours), m.npuOursPercent, meters.history.map { it.npuOursPercent }, Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.lecture_c_last_minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalTranscriptorColors.current.time,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            HorizontalDivider()
        }
    }
}

private fun percent(p: Int?) = p?.let { "$it%" } ?: "–"

/** A label and figure over a 60 s graph that fills from the start side: newest at the end. */
@Composable
private fun Graph(label: String, now: Int?, history: List<Int?>, modifier: Modifier) {
    val color = meterColor(now)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time, maxLines = 1, modifier = Modifier.weight(1f))
            Text(percent(now), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(22.dp).padding(top = 2.dp)) {
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            if (history.size < 2) return@Canvas
            val step = size.width / (120 - 1)
            val first = 120 - history.size
            fun x(i: Int) = if (rtl) size.width - (first + i) * step else (first + i) * step
            fun y(v: Int?) = size.height - (v ?: 0) / 100f * size.height
            val line = Path().apply {
                history.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(x(history.lastIndex), size.height)
                lineTo(x(0), size.height)
                close()
            }
            drawPath(area, color.copy(alpha = 0.18f), style = Fill)
            drawPath(line, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(color, 2.5.dp.toPx(), Offset(x(history.lastIndex), y(history.last())))
        }
    }
}
