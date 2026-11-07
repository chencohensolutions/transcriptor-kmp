package com.chencohensolution.transcriptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.transcriptor.recording.Clock
import com.chencohensolution.transcriptor.recording.CoreClock
import com.chencohensolution.transcriptor.recording.ProcessorMeterSampler
import com.chencohensolution.transcriptor.recording.ProcessorMeters
import com.chencohensolution.transcriptor.recording.ProcessorSample
import com.chencohensolution.transcriptor.settings.ProcessorMeterStyle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

// Fakes: the processors' counters while recording with Whisper large-v3 on the NPU in ~30 s windows: for about 10 s of
// every 30 the NPU is busy with a window and the CPU feeds it; in between only the recording, FLAC and the screen run.
// They go through the shared ProcessorMeterSampler once a second, as the app's AndroidProcessorProbe does.

/** The lecture screen with the meters in [style] (Settings › Advanced), fed fake readings. */
private fun lectureWithMeters(id: String, title: String, style: ProcessorMeterStyle) = LabOption(id, title, "LectureScreen with faked processor readings") {
    val (state, actions) = rememberGrowingLectureLab()
    val (now, history) = rememberFakeMeters(state.paused)
    LectureScreen(state.copy(meterStyle = style, meters = now, meterHistory = history), actions)
}

internal val lectureCurrentBars = lectureWithMeters("current-bars", "Processor meters: three bars (the app's)", ProcessorMeterStyle.Bars)
internal val lectureCurrentChip = lectureWithMeters("current-chip", "Processor meters: a chip and its sheet (the app's)", ProcessorMeterStyle.Chip)
internal val lectureCurrentGraphs = lectureWithMeters("current-graphs", "Processor meters: the last minute as graphs (the app's)", ProcessorMeterStyle.Graphs)

@Composable
private fun rememberFakeMeters(paused: Boolean): Pair<ProcessorMeters?, List<ProcessorMeters>> {
    var meters by remember { mutableStateOf<Pair<ProcessorMeters?, List<ProcessorMeters>>>(null to emptyList()) }
    val fake = remember { FakeProcessors() }
    val sampler = remember { ProcessorMeterSampler(fake) }
    LaunchedEffect(paused) {
        fake.paused = paused
        while (true) {
            val now = sampler.sample()
            if (sampler.history.isNotEmpty()) meters = now to sampler.history
            delay(1_000)
            fake.advance()
        }
    }
    return meters
}

private class FakeProcessors : com.chencohensolution.transcriptor.recording.ProcessorProbe {
    var paused = false
    private val random = Random(7)
    private var t = 22_000L // a window starts 8 s after opening
    private var cpuSeconds = 0.0
    private var npuMillis = 0L
    private var temperature = 41_200
    private var sample = next()

    fun advance() {
        t += 1_000
        sample = next()
    }

    override fun sample() = sample

    private fun next(): ProcessorSample {
        val phase = t % 30_000
        val window = !paused && phase < 10_000
        val cores = when {
            paused -> 0.08 + random.nextDouble() * 0.05
            window && phase < 2_000 -> 2.4 + random.nextDouble() * 0.9 // the window's features, on the CPU
            window -> 0.9 + random.nextDouble() * 0.5
            else -> 0.3 + random.nextDouble() * 0.15
        }
        cpuSeconds += cores
        if (window) npuMillis += (1_000 * (0.82 + random.nextDouble() * 0.15)).toLong()
        temperature = (temperature + if (window) 140 else -50).coerceIn(38_000, 47_000)
        val little = (960 + cores / 3.4 * 2_570).roundToInt().coerceAtMost(3_532)
        val big = if (window && phase < 2_000) 3_800 + random.nextInt(600) else 1_017
        val gpu = if (random.nextFloat() < 0.15f) 9 + random.nextInt(10) else random.nextInt(5)
        return ProcessorSample(
            atMillis = t,
            cpuSeconds = cpuSeconds,
            cpuCores = 8,
            npuBusyMillis = npuMillis,
            gpuBusyPercent = gpu,
            cpuClocks = listOf(CoreClock(0, 5, Clock(little, 3_532)), CoreClock(6, 7, Clock(big.coerceAtMost(4_473), 4_473))),
            gpuClock = Clock(if (gpu > 8) 380 else 222, 832),
            npuTemperatureMilliC = temperature,
        )
    }
}
