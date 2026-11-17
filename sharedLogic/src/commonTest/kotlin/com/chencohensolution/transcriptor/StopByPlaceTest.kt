package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.location.DrivingSwitch
import com.chencohensolution.transcriptor.location.Fix
import com.chencohensolution.transcriptor.location.FixSource
import com.chencohensolution.transcriptor.location.GeoPoint
import com.chencohensolution.transcriptor.location.LocationSettings
import com.chencohensolution.transcriptor.location.MomentPath
import com.chencohensolution.transcriptor.location.PathBuilder
import com.chencohensolution.transcriptor.location.PathPoint
import com.chencohensolution.transcriptor.location.PlaceWatch
import com.chencohensolution.transcriptor.location.PlaceWatch.State
import com.chencohensolution.transcriptor.location.SavedPlace
import com.chencohensolution.transcriptor.location.StopReason
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.PlaceStop
import com.chencohensolution.transcriptor.settings.QualityProfile
import com.chencohensolution.transcriptor.settings.SettingsStore
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StopByPlaceTest {
    private val home = GeoPoint(32.0853, 34.7818)

    /** A point [north] metres north of [home]. */
    private fun north(m: Double) = GeoPoint(home.lat + m / 111_195.0, home.lon)

    private fun fix(atSec: Long, m: Double, acc: Float = 10f, speed: Float? = null) = Fix(atSec * 1000, north(m), acc, speed)

    @Test
    fun distanceIsRight() {
        assertTrue(abs(home.metersTo(north(1000.0)) - 1000.0) < 2.0)
    }

    @Test
    fun leavingStopsAfterTwoFixesAMinuteApartThenTheCountdown() {
        val w = PlaceWatch(PlaceWatch.Config(leaveMeters = 250))
        assertEquals(State.NoFix, w.state)
        w.fix(fix(0, 0.0))
        assertIs<State.Watching>(w.state)
        w.fix(fix(600, 400.0)) // first fix outside: not yet
        assertIs<State.Watching>(w.state)
        val counting = assertIs<State.Counting>(w.fix(fix(661, 600.0)))
        assertEquals(StopReason.Left, counting.reason)
        assertEquals(661_000, counting.firedAtMillis)
        assertIs<State.Counting>(w.tick(700_000))
        val stop = assertIs<State.Stop>(w.tick(721_000))
        assertEquals(661_000, stop.firedAtMillis)
    }

    @Test
    fun indoorDriftAndPoorFixesDontStop() {
        val w = PlaceWatch(PlaceWatch.Config(leaveMeters = 100))
        w.fix(fix(0, 0.0))
        // A bad fix far away (accuracy worse than half the distance) is ignored.
        w.fix(fix(60, 300.0, acc = 80f))
        w.fix(fix(200, 300.0, acc = 80f))
        // One good fix outside, then back inside: starts over.
        w.fix(fix(300, 150.0))
        w.fix(fix(330, 20.0))
        w.fix(fix(400, 150.0))
        assertIs<State.Watching>(w.state)
        assertIs<State.Watching>(w.fix(fix(430, 160.0)))
    }

    @Test
    fun keepRecordingDisarmsThatTrigger() {
        val w = PlaceWatch(PlaceWatch.Config(leaveMeters = 250, device = true))
        w.fix(fix(0, 0.0))
        w.fix(fix(100, 400.0))
        w.fix(fix(200, 400.0))
        assertIs<State.Counting>(w.state)
        assertIs<State.Watching>(w.keepRecording())
        w.fix(fix(400, 900.0))
        w.fix(fix(500, 900.0))
        assertIs<State.Watching>(w.state) // leaving is off now
        assertEquals(StopReason.Device, assertIs<State.Counting>(w.deviceConnected(510_000)).reason)
        assertEquals(State.Off, w.keepRecording())
    }

    @Test
    fun arrivingOnlyAtAPlaceLeftFirst() {
        val homePlace = SavedPlace("h", "בית", home)
        val w = PlaceWatch(PlaceWatch.Config(arrive = listOf(homePlace)))
        // Starts at home: not armed, staying doesn't stop it.
        w.fix(fix(0, 0.0))
        w.fix(fix(100, 10.0))
        assertIs<State.Watching>(w.state)
        // Away, then back for a minute.
        w.fix(fix(1000, 3000.0))
        w.fix(fix(4000, 50.0))
        val c = assertIs<State.Counting>(w.fix(fix(4070, 30.0)))
        assertEquals(StopReason.Arrived, c.reason)
        assertEquals("בית", c.place?.name)
    }

    @Test
    fun reanchorMeasuresFromTheNewPlace() {
        val w = PlaceWatch(PlaceWatch.Config(leaveMeters = 250))
        w.fix(fix(0, 0.0))
        w.reanchor(fix(10, 1000.0))
        w.fix(fix(100, 1100.0))
        w.fix(fix(200, 1100.0))
        assertIs<State.Watching>(w.state)
        assertEquals(north(1000.0), (w.state as State.Watching).anchor)
    }

    @Test
    fun settingsGiveTheWatchItsConfig() {
        val place = SavedPlace("w", "עבודה", home)
        val s = LocationSettings(stopByPlace = true, stopMeters = 100, stopOnArrive = true, places = listOf(place))
        assertEquals(PlaceWatch.Config(leaveMeters = 100, arrive = listOf(place)), s.watchConfig(onForProfile = true))
        assertFalse(s.watchConfig(onForProfile = false).anything)
        assertEquals(500, s.copy(approximateOnly = true).leaveMeters)
        assertFalse(LocationSettings().wantsLocation)
        assertFalse(LocationSettings().watchConfig(true).anything)
    }

    @Test
    fun settingsKeepLocationAndMusicStartsWithoutStopByPlace() {
        val fs = FakeFileSystem()
        val store = SettingsStore(fs, "/s".toPath())
        assertFalse(store.location.stopByPlace)
        assertFalse(store.profile(QualityProfile.Music).stopByPlace)
        assertTrue(store.profile(QualityProfile.Normal).stopByPlace)
        store.updateLocation { it.copy(stopByPlace = true, places = listOf(SavedPlace("h", "בית", home))) }
        val again = SettingsStore(fs, "/s".toPath())
        assertTrue(again.location.stopByPlace)
        assertEquals("בית", again.location.places.single().name)
    }

    @Test
    fun pathIsThinned() {
        val b = PathBuilder { it - 1_000_000 }
        assertTrue(b.add(Fix(1_000_000, home, 5f)))
        assertFalse(b.add(Fix(1_000_500, north(30.0), 5f))) // under a second
        assertFalse(b.add(Fix(1_010_000, north(2.0), 5f))) // didn't move
        assertTrue(b.add(Fix(1_020_000, north(50.0), 5f, speedMps = 5f)))
        assertTrue(b.add(Fix(1_080_000, north(51.0), 5f))) // still, but a minute on
        val path = b.path()
        assertEquals(listOf(0L, 20_000L, 80_000L), path.points.map { it.atMillis })
        assertTrue(abs(path.distanceMeters() - 51.0) < 1.0)
        assertEquals(5f, path.topSpeedMps())
    }

    @Test
    fun drivingSwitchTurnsOnWhenFastAndOffTwoMinutesLater() {
        val d = DrivingSwitch()
        assertFalse(d.update(fix(0, 0.0, speed = 2f)))
        assertTrue(d.update(fix(10, 0.0, speed = 10f)))
        assertTrue(d.update(fix(100, 0.0, speed = 1f)))
        assertFalse(d.update(fix(131, 0.0, speed = 1f)))
    }

    @Test
    fun pathAndStopReasonAreKeptWithTheMoment() {
        val fs = FakeFileSystem()
        val store = LectureStore(fs, "/data".toPath())
        val lecture = Lecture("m", "מפגש", "he", 1, 60_000, emptyList(), emptyList(), placeStop = PlaceStop("left", 50_000), hasPath = true)
        store.save(lecture)
        val path = MomentPath(
            listOf(
                PathPoint(0, home, 8f, null, null, FixSource.Passive),
                PathPoint(1000, north(12.0), 4f, 12.5f, 90f, FixSource.Gps),
            ),
            audioEndMillis = 60_000,
        )
        store.savePath(lecture, path)
        val back = assertNotNull(store.load("m"))
        assertEquals(PlaceStop("left", 50_000), back.placeStop)
        assertTrue(back.hasPath)
        assertEquals(path, store.loadPath(back))
        // A moment from before has neither.
        store.save(Lecture("old", "t", "he", 1, 1, emptyList(), emptyList()))
        val old = assertNotNull(store.load("old"))
        assertNull(old.placeStop)
        assertNull(store.loadPath(old))
    }
}
