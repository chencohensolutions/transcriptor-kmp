package com.chencohensolution.transcriptor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chencohensolution.transcriptor.recording.Clock
import com.chencohensolution.transcriptor.recording.CoreClock
import com.chencohensolution.transcriptor.recording.ProcessorMeters
import com.chencohensolution.transcriptor.recording.ProcessorSample
import com.chencohensolution.transcriptor.recording.processorMeters
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

// Fakes: the processors' counters while recording with Whisper large-v3 on the NPU in ~30 s windows (PR #11): for
// about 10 s of every 30 the NPU is busy with a window and the CPU feeds it; in between only the recording, FLAC and
// the screen run. The counters go through the shared processorMeters(), as the controller's readers would.

/** The meters now and the last minute of them (a reading every half second), oldest first. */
internal class LabMeters(val now: ProcessorMeters, val history: List<ProcessorMeters>)

private const val STEP_MILLIS = 500L
private const val HISTORY = 120

@Composable
internal fun rememberFakeMeters(paused: Boolean = false): LabMeters {
    var meters by remember { mutableStateOf(LabMeters(fakeStart, emptyList())) }
    LaunchedEffect(paused) {
        val random = Random(7)
        var t = 22_000L // a window starts 8 s after opening
        var cpuSeconds = 0.0
        var npuMillis = 0L
        var temperature = 41_200
        var previous: ProcessorSample? = null
        var history = meters.history
        while (true) {
            val phase = t % 30_000
            val window = !paused && phase < 10_000
            val cores = when {
                paused -> 0.08 + random.nextDouble() * 0.05
                window && phase < 1_500 -> 2.4 + random.nextDouble() * 0.9 // the window's features, on the CPU
                window -> 0.9 + random.nextDouble() * 0.5
                else -> 0.3 + random.nextDouble() * 0.15
            }
            cpuSeconds += cores * STEP_MILLIS / 1000
            if (window) npuMillis += (STEP_MILLIS * (0.82 + random.nextDouble() * 0.15)).toLong()
            temperature = (temperature + if (window) 70 else -25).coerceIn(38_000, 47_000)
            val little = (960 + cores / 3.4 * 2_570).roundToInt().coerceAtMost(3_532)
            val big = if (window && phase < 1_500) 3_800 + random.nextInt(600) else 1_017
            val gpu = if (random.nextFloat() < 0.15f) 9 + random.nextInt(10) else random.nextInt(5)
            val sample = ProcessorSample(
                atMillis = t,
                cpuSeconds = cpuSeconds,
                cpuCores = 8,
                npuBusyMillis = npuMillis,
                gpuBusyPercent = gpu,
                cpuClocks = listOf(CoreClock(0, 5, Clock(little, 3_532)), CoreClock(6, 7, Clock(big.coerceAtMost(4_473), 4_473))),
                gpuClock = Clock(if (gpu > 8) 380 else 222, 832),
                npuTemperatureMilliC = temperature,
            )
            if (previous != null) {
                val now = processorMeters(previous, sample)
                history = (history + now).takeLast(HISTORY)
                meters = LabMeters(now, history)
            }
            previous = sample
            t += STEP_MILLIS
            delay(STEP_MILLIS)
        }
    }
    return meters
}

private val fakeStart = ProcessorMeters(
    cpuPercent = null, cpuCoresBusy = null, cpuCores = 8, gpuPercent = 0, npuOursPercent = null,
    cpuClocks = listOf(CoreClock(0, 5, Clock(2_745, 3_532)), CoreClock(6, 7, Clock(1_017, 4_473))),
    gpuClock = Clock(222, 832), npuTemperatureC = 41.2f,
)

/** A meter's color: the theme's, amber when high, red when nearly full. */
@Composable
internal fun meterColor(percent: Int?): Color {
    val target = when {
        percent == null -> MaterialTheme.colorScheme.outlineVariant
        percent >= 90 -> MaterialTheme.colorScheme.error
        percent >= 70 -> Color(0xFFE09A2B)
        else -> MaterialTheme.colorScheme.primary
    }
    return animateColorAsState(target, tween(400), label = "meter color").value
}

/** A thin horizontal bar, filled to [percent] (0 when unknown), gliding between readings. */
@Composable
internal fun MeterBar(percent: Int?, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val fill by animateFloatAsState((percent ?: 0) / 100f, tween(450), label = "meter")
    val color = meterColor(percent)
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(height / 2)).background(color))
    }
}

/**
 * Lays [content] just under LectureScreen's top bar (status bar, its 60 dp row and divider), over the top of the
 * transcript: the mocks draw the meters on the screen as the app has it, without a copy of it.
 */
@Composable
internal fun UnderLectureTopBar(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 61.dp), content = content)
}
