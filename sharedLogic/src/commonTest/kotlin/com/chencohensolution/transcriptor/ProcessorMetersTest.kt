package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.recording.Clock
import com.chencohensolution.transcriptor.recording.CoreClock
import com.chencohensolution.transcriptor.recording.NpuBusyClock
import com.chencohensolution.transcriptor.recording.ProcessorMeterSampler
import com.chencohensolution.transcriptor.recording.ProcessorSample
import com.chencohensolution.transcriptor.recording.processorMeters
import com.chencohensolution.transcriptor.settings.ProcessorMeterStyle
import com.chencohensolution.transcriptor.settings.SettingsStore
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessorMetersTest {
    private fun sample(at: Long, cpu: Double?, npu: Long? = null, gpu: Int? = null) =
        ProcessorSample(atMillis = at, cpuSeconds = cpu, cpuCores = 8, npuBusyMillis = npu, gpuBusyPercent = gpu)

    @Test
    fun cpuIsTheAppsTimeAsAShareOfAllCores() {
        // 2.4 s of CPU in 1 s: 2.4 cores busy, 30% of 8.
        val m = processorMeters(sample(1_000, 10.0), sample(2_000, 12.4))
        assertEquals(2.4f, m.cpuCoresBusy!!, 0.001f)
        assertEquals(30, m.cpuPercent)
        assertEquals(8, m.cpuCores)
    }

    @Test
    fun cpuNeverPassesAllCores() {
        val m = processorMeters(sample(0, 0.0), sample(1_000, 20.0))
        assertEquals(100, m.cpuPercent)
        assertEquals(8f, m.cpuCoresBusy)
    }

    @Test
    fun withoutAnEarlierReadingUseIsUnknown() {
        val now = sample(1_000, 5.0, npu = 300, gpu = 12)
        val first = processorMeters(null, now)
        assertNull(first.cpuPercent)
        assertNull(first.npuOursPercent)
        assertEquals(12, first.gpuPercent)
        // A reading at the same time (or a clock gone back) is no interval either.
        assertNull(processorMeters(now, now).cpuPercent)
        assertNull(processorMeters(sample(2_000, 4.0), now).cpuPercent)
    }

    @Test
    fun npuIsOurBusyTimeOverTheInterval() {
        val m = processorMeters(sample(0, 0.0, npu = 1_000), sample(2_000, 1.0, npu = 2_700))
        assertEquals(85, m.npuOursPercent)
        // No NPU model running: no NPU meter.
        assertNull(processorMeters(sample(0, 0.0), sample(2_000, 1.0)).npuOursPercent)
    }

    @Test
    fun clocksAndTemperatureComeFromTheLatestReading() {
        val big = CoreClock(6, 7, Clock(1_017, 4_473))
        val little = CoreClock(0, 5, Clock(2_745, 3_532))
        val now = sample(1_000, 1.0).copy(cpuClocks = listOf(big, little), gpuClock = Clock(222, 832), npuTemperatureMilliC = 38_700)
        val m = processorMeters(null, now)
        assertEquals(listOf(little, big), m.cpuClocks)
        assertEquals(27, m.gpuClock!!.percent)
        assertEquals(78, little.clock.percent)
        assertEquals(38.7f, m.npuTemperatureC!!, 0.001f)
        assertEquals(0, Clock(100, 0).percent)
    }

    @Test
    fun anUnreadableCounterHidesItsMeter() {
        val m = processorMeters(sample(0, null, gpu = null), sample(1_000, null, gpu = null))
        assertNull(m.cpuPercent)
        assertNull(m.cpuCoresBusy)
        assertNull(m.gpuPercent)
        assertNull(m.gpuClock)
        assertNull(m.npuTemperatureC)
    }

    @Test
    fun theSamplerKeepsTheLastMinute() {
        var t = 0L
        val sampler = ProcessorMeterSampler({ sample(t, t / 1000.0) }, keep = 3)
        assertNull(sampler.sample().cpuPercent)
        assertEquals(emptyList(), sampler.history)
        repeat(5) { t += 1_000; sampler.sample() }
        assertEquals(3, sampler.history.size)
        assertEquals(13, sampler.history.last().cpuPercent) // 1 core of 8
        // After a gap (the screen hidden), the next reading starts afresh instead of spanning the gap.
        sampler.reset()
        t += 60_000
        assertNull(sampler.sample().cpuPercent)
        assertEquals(emptyList(), sampler.history)
    }

    @Test
    fun npuCallsThatOverlapCountOnce() {
        var now = 0L
        val npu = NpuBusyClock { now }
        assertNull(npu.busyMillis)
        npu.begin(); now = 100
        npu.begin(); now = 300 // a second call while the first runs
        npu.end(); now = 400
        npu.end(); now = 1_000
        assertEquals(400, npu.busyMillis)
        // A call running now counts up to now; an extra end() changes nothing.
        npu.time { now = 1_250; assertEquals(650, npu.busyMillis) }
        npu.end()
        assertEquals(650, npu.busyMillis)
    }

    @Test
    fun theMeterStyleIsASettingOffByDefault() {
        val fs = FakeFileSystem()
        val root = "/files".toPath()
        assertEquals(ProcessorMeterStyle.Off, SettingsStore(fs, root).processorMeters)
        SettingsStore(fs, root).processorMeters = ProcessorMeterStyle.Graphs
        assertEquals(ProcessorMeterStyle.Graphs, SettingsStore(fs, root).processorMeters)
        assert("\"processorMeters\": \"graphs\"" in fs.read(root / "settings.json") { readUtf8() })
        // A style a newer version wrote reads as off, and the other settings still read.
        val file = root / "settings.json"
        val newer = fs.read(file) { readUtf8() }.replace("\"graphs\"", "\"radar\"")
        fs.write(file) { writeUtf8(newer) }
        assertEquals(ProcessorMeterStyle.Off, SettingsStore(fs, root).processorMeters)
        assertEquals(ProcessorMeterStyle.Off, ProcessorMeterStyle.of(null))
    }
}
