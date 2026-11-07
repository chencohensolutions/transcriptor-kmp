package com.chencohensolution.transcriptor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R
import com.chencohensolution.transcriptor.recording.Clock
import com.chencohensolution.transcriptor.recording.ProcessorMeters

// Option b: a small chip under the top bar's end with three tiny bars (CPU, GPU, NPU); tapping it opens a sheet with
// each meter explained, the clocks (cores 0–5, cores 6–7, the GPU) and the NPU's temperature. Fakes: the meters
// (LectureMetersLab) and the recording (LectureGrowingLab).
internal val lectureOptionB = LabOption("b", "Meters: a chip that opens a sheet", "Three tiny bars; tap for bars, clocks and the NPU's temperature") {
    val (state, actions) = rememberGrowingLectureLab()
    val meters = rememberFakeMeters(state.paused)
    var open by remember { mutableStateOf(false) }
    Box {
        LectureScreen(state.copy(meters = meters.now), actions)
        UnderLectureTopBar {
            MetersChip(meters.now, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)) { open = true }
        }
    }
    if (open) MetersSheet(meters.now) { open = false }
}

@Composable
private fun MetersChip(m: ProcessorMeters, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(R.string.lecture_b_chip_cpu to m.cpuPercent, R.string.lecture_b_chip_gpu to m.gpuPercent, R.string.lecture_b_chip_npu to m.npuOursPercent).forEach { (label, percent) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 3.dp)) {
                    TinyColumn(percent)
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time)
                }
            }
        }
    }
}

/** A short upright bar, 18 dp tall when full. */
@Composable
private fun TinyColumn(percent: Int?) {
    val fill by animateFloatAsState((percent ?: 0) / 100f, tween(450), label = "tiny")
    Box(Modifier.width(7.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxWidth().height(18.dp * fill.coerceAtLeast(0.06f)).background(meterColor(percent)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetersSheet(m: ProcessorMeters, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.lecture_b_title), style = MaterialTheme.typography.titleLarge)
            SheetMeter(
                stringResource(R.string.lecture_b_cpu), m.cpuPercent,
                stringResource(R.string.lecture_b_cpu_note, m.cpuCoresBusy ?: 0f, m.cpuCores),
            )
            SheetMeter(stringResource(R.string.lecture_b_gpu), m.gpuPercent, stringResource(R.string.lecture_b_gpu_note))
            SheetMeter(stringResource(R.string.lecture_b_npu), m.npuOursPercent, stringResource(R.string.lecture_b_npu_note))
            HorizontalDivider()
            Text(stringResource(R.string.lecture_b_clocks), style = MaterialTheme.typography.titleSmall)
            m.cpuClocks.forEach { group ->
                ClockRow(stringResource(R.string.lecture_b_cores, group.first, group.last), group.clock, ghz = true)
            }
            m.gpuClock?.let { ClockRow(stringResource(R.string.lecture_b_gpu_clock), it, ghz = false) }
            m.npuTemperatureC?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(meterColor(((t - 30f) * 100 / 50f).toInt())))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.lecture_b_npu_temp), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.lecture_b_celsius, t), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SheetMeter(label: String, percent: Int?, note: String) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(percent?.let { "$it%" } ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                if (ghz) stringResource(R.string.lecture_b_ghz, clock.currentMhz / 1000f, clock.maxMhz / 1000f)
                else stringResource(R.string.lecture_b_mhz, clock.currentMhz, clock.maxMhz),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        MeterBar(clock.percent, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
