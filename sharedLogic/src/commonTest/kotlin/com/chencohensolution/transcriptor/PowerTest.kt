package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.recording.PowerLogCsv
import com.chencohensolution.transcriptor.recording.PowerMeter
import com.chencohensolution.transcriptor.recording.PowerModel
import com.chencohensolution.transcriptor.recording.PowerReport
import com.chencohensolution.transcriptor.recording.PowerSample
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerTest {
    private fun sample(t: Long, microAmps: Long, charging: Boolean = false, temp: Int = 300, level: Int = 80, cpu: Double = 0.0) =
        PowerSample(t, microAmps, 4_000, charging, screenOn = t < 60_000, temperatureTenths = temp, levelPercent = level, cpuSeconds = cpu)

    @Test
    fun aTwoMinuteLectureAtHalfAnAmpIsTwoWatts() {
        // -500 mA (the S25 reports discharge as negative) at 4.0 V: 2 W.
        val samples = listOf(sample(0, -500_000), sample(60_000, -500_000, temp = 330, level = 79, cpu = 30.0), sample(120_000, -500_000, temp = 340, level = 78, cpu = 60.0))
        val r = PowerReport.of(samples, 120_000, listOf(PowerModel("live", "Zipformer · NPU", "NPU")))
        assertEquals(2000.0, r.averageMilliwatts)
        assertEquals(66.67, r.totalMilliwattHours)
        assertEquals(33.33, r.milliwattHoursPerMinute)
        assertEquals(120.0, r.joulesPerMinute)
        assertEquals(30.0, r.cpuSecondsPerMinute)
        assertEquals(4.0, r.temperatureRise!!, 1e-9)
        assertEquals(80, r.batteryPercentStart)
        assertEquals(78, r.batteryPercentEnd)
        assertEquals(0.33, r.screenOnShare)
    }

    @Test
    fun onTheChargerThePowerIsNotMeasured() {
        val r = PowerReport.of(listOf(sample(0, 2_000_000, charging = true), sample(10_000, -400_000)), 10_000, emptyList())
        assertTrue(r.charging)
        assertNull(r.averageMilliwatts)
        assertNull(r.joulesPerMinute)
    }

    @Test
    fun theReportIsKeptWithTheLectureAndLogged() = runTest {
        val fs = FakeFileSystem()
        val store = LectureStore(fs, "/d".toPath())
        val lecture = Lecture(id = "l1", title = "t", language = "he", startedAtMillis = 5, durationMillis = 60_000, segments = emptyList(), notes = emptyList())
        store.save(lecture)
        val r = PowerReport.of(listOf(sample(0, -300_000), sample(60_000, -300_000)), 60_000, listOf(PowerModel("speakers", "ResNet293", "NPU")))
        store.savePower(lecture, r)
        assertEquals(r, store.loadPower(lecture))
        store.appendPowerLog(PowerLogCsv.row("l1", "nb", 5, r))
        store.appendPowerLog(PowerLogCsv.row("l2", "nb", 6, r))
        val csv = fs.read("/d/power-log.csv".toPath()) { readUtf8() }.trim().lines()
        assertEquals(PowerLogCsv.HEADER, csv[0])
        assertEquals(3, csv.size)
        assertTrue(csv[1].startsWith("l1,nb,5,60,1200.0,"), csv[1])
        assertTrue(csv[1].endsWith("\"speakers: ResNet293 (NPU)\""), csv[1])
    }

    @Test
    fun theMeterSamplesUntilStopped() = runTest {
        var n = 0L
        val meter = PowerMeter({ sample(n++ * 10_000, -100_000) }, intervalMillis = 10_000)
        meter.start(backgroundScope)
        testScheduler.advanceTimeBy(25_000)
        testScheduler.runCurrent()
        val samples = meter.stop()
        assertEquals(4, samples.size) // at 0, 10 and 20 s, and the last one at Stop
    }
}
