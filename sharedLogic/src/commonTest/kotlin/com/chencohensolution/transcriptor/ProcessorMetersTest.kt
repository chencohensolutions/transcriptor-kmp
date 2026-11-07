package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.recording.Clock
import com.chencohensolution.transcriptor.recording.CoreClock
import com.chencohensolution.transcriptor.recording.ProcessorSample
import com.chencohensolution.transcriptor.recording.processorMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessorMetersTest {
    private fun sample(at: Long, cpu: Double, npu: Long? = null, gpu: Int? = null) =
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
}
