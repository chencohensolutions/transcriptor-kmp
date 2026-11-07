package com.chencohensolution.transcriptor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.R

// Option a: a slim strip of three meters under the top bar, always there: CPU (this app, % of all 8 cores), GPU (the
// whole phone), NPU (our use only). Fakes: the meters (LectureMetersLab) and the recording (LectureGrowingLab).
internal val lectureOptionA = LabOption("a", "Meters: a slim strip under the top bar", "CPU (ours, of all cores) · GPU (phone) · NPU (our use), always shown") {
    val (state, actions) = rememberGrowingLectureLab()
    val meters = rememberFakeMeters(state.paused)
    Box {
        LectureScreen(state.copy(meters = meters.now), actions)
        UnderLectureTopBar { MetersStrip(meters.now) }
    }
}

@Composable
private fun MetersStrip(m: com.chencohensolution.transcriptor.recording.ProcessorMeters) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StripMeter(stringResource(R.string.lecture_a_cpu), m.cpuPercent, Modifier.weight(1f))
                StripMeter(stringResource(R.string.lecture_a_gpu), m.gpuPercent, Modifier.weight(1f))
                StripMeter(stringResource(R.string.lecture_a_npu_ours), m.npuOursPercent, Modifier.weight(1.3f))
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun StripMeter(label: String, percent: Int?, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = LocalTranscriptorColors.current.time, maxLines = 1)
            Spacer(Modifier.weight(1f).width(4.dp))
            Text(percent?.let { "$it%" } ?: "–", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        MeterBar(percent, Modifier.fillMaxWidth().padding(top = 3.dp))
    }
}
