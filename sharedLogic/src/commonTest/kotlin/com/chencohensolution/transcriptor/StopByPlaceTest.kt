package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.location.MovingSwitch
import com.chencohensolution.transcriptor.location.Fix
import com.chencohensolution.transcriptor.location.FixSource
import com.chencohensolution.transcriptor.location.GeoPoint
import com.chencohensolution.transcriptor.location.KeepUntil
import com.chencohensolution.transcriptor.location.LocationSettings
import com.chencohensolution.transcriptor.location.MomentLocation
import com.chencohensolution.transcriptor.location.MomentPath
import com.chencohensolution.transcriptor.location.PathNoteV1
import com.chencohensolution.transcriptor.location.PathPrecision
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
    fun everyFixIsKeptButTheSameOneTwice() {
        val b = PathBuilder { it - 1_000_000 }
        assertTrue(b.add(Fix(1_000_000, home, 5f)))
        assertFalse(b.add(Fix(1_000_100, home, 5f, source = FixSource.Gps))) // the same fix, heard by the other listener
        assertTrue(b.add(Fix(1_001_000, north(0.3), 5f))) // standing still: kept anyway
        assertTrue(b.add(Fix(1_020_000, north(50.0), 5f, speedMps = 5f)))
        assertFalse(b.add(Fix(1_010_000, north(10.0), 5f))) // an older one arriving late
        val path = b.path()
        assertEquals(listOf(0L, 1_000L, 20_000L), path.points.map { it.atMillis })
        assertTrue(abs(path.distanceMeters() - 50.0) < 1.0)
        assertEquals(5f, path.topSpeedMps())
    }

    @Test
    fun movingSwitchTurnsOnAtASlowWalkAndOffTwoMinutesLater() {
        val d = MovingSwitch()
        assertFalse(d.update(fix(0, 0.0, speed = 0.1f))) // standing, GPS noise
        assertFalse(d.update(fix(5, 4.0, speed = 0.1f))) // drift of a few metres isn't moving
        assertTrue(d.update(fix(10, 4.0, speed = 0.5f))) // a slow walk, about 1.8 km/h
        assertTrue(d.update(fix(100, 4.0, speed = 0.1f)))
        assertFalse(d.update(fix(131, 4.0, speed = 0.1f)))
        // Without speed (network fixes): moving 15 m or more counts.
        assertTrue(d.update(Fix(140_000, north(25.0), 8f)))
        // The motion sensor counts on its own.
        val s = MovingSwitch()
        s.motion(1_000)
        assertTrue(s.on(60_000))
    }

    @Test
    fun aSlowWalkKeepsEveryPoint() {
        val b = PathBuilder { it }
        (0..10).forEach { s -> assertTrue(b.add(Fix(s * 1000L, north(s * 0.5), 4f, speedMps = 0.5f))) }
        assertEquals(11, b.size)
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

    @Test
    fun momentLocationAsksForGpsUntilAnchoredThenOnceAMinute() {
        val s = LocationSettings(stopByPlace = true, stopMeters = 250)
        val m = MomentLocation(s, stopOn = true, pathOn = true) { it - 1_000_000 }
        assertEquals(MomentLocation.Request.EverySecond, m.status.value.request)
        m.fix(Fix(1_000_000, home, 10f))
        assertEquals(MomentLocation.Request.Minute, m.status.value.request)
        assertNull(m.status.value.points) // path saving is off in the settings
        m.fix(Fix(1_100_000, north(400.0), 10f))
        m.fix(Fix(1_170_000, north(500.0), 10f))
        assertTrue(m.status.value.stopping)
        assertEquals(170_000, m.firedAtRecording())
        m.tick(1_230_000)
        assertEquals(com.chencohensolution.transcriptor.notebook.PlaceStop("left", 170_000), m.placeStop())
        assertEquals(MomentLocation.Request.None, m.status.value.request)
    }

    @Test
    fun aCachedFixFromBeforeTheStartIsLeftOut() {
        val m = MomentLocation(LocationSettings(stopByPlace = true, savePath = true), stopOn = true, pathOn = true, startedAtMillis = 1_000_000) { it }
        m.fix(Fix(900_000, north(5000.0), 5f)) // an hour-old fix from elsewhere
        assertEquals(PlaceWatch.State.NoFix, m.status.value.watch)
        assertEquals(0, m.status.value.points)
        m.fix(Fix(995_000, home, 5f)) // taken as Record was pressed
        assertEquals(PlaceWatch.State.Watching(home), m.status.value.watch)
    }

    @Test
    fun passivePathAsksForNothingAndDrivingOnlyWhenFast() {
        val passive = MomentLocation(LocationSettings(savePath = true), stopOn = false, pathOn = true) { it }
        assertTrue(passive.active)
        assertEquals(MomentLocation.Request.None, passive.status.value.request)
        passive.fix(Fix(1_000, home, 5f))
        assertEquals(1, passive.status.value.points)
        val moving = MomentLocation(LocationSettings(savePath = true, precision = PathPrecision.WhileMoving.key), stopOn = false, pathOn = true, motionSensor = true) { it }
        moving.fix(Fix(1_000, home, 5f, speedMps = 0.1f))
        assertEquals(MomentLocation.Request.None, moving.status.value.request) // standing; the sensor will wake it
        moving.motion(2_000)
        assertEquals(MomentLocation.Request.EverySecond, moving.status.value.request)
        // No motion sensor: a fix a minute notices the walk.
        assertEquals(MomentLocation.Request.Minute, MomentLocation(LocationSettings(savePath = true, precision = PathPrecision.WhileMoving.key), stopOn = false, pathOn = true) { it }.status.value.request)
        // Off for this moment's profile: nothing at all.
        assertFalse(MomentLocation(LocationSettings(stopByPlace = true, savePath = true), stopOn = false, pathOn = false) { it }.active)
    }

    @Test
    fun thePathGoesOnAfterStopUntilASavedPlace() {
        val homePlace = SavedPlace("h", "בית", north(5000.0))
        val s = LocationSettings(savePath = true, keepAfterStop = true)
        // Recording time = clock - 1,000,000 while recording.
        val m = MomentLocation(s, stopOn = false, pathOn = true) { it - 1_000_000 }
        m.fix(Fix(1_000_000, home, 5f))
        m.fix(Fix(1_600_000, north(100.0), 5f))
        assertTrue(m.keepGoing(1_600_000, audioEndMillis = 600_000, until = KeepUntil.SavedPlace, places = listOf(homePlace)))
        assertEquals(MomentLocation.Request.Minute, m.status.value.request) // watching for home
        m.fix(Fix(1_900_000, north(3000.0), 5f, speedMps = 20f))
        assertFalse(m.keepingDone(1_900_000))
        m.fix(Fix(2_200_000, north(5010.0), 5f))
        m.fix(Fix(2_270_000, north(5005.0), 5f))
        assertTrue(m.keepingDone(2_270_000))
        val path = assertNotNull(m.keptPath())
        assertEquals(600_000, path.audioEndMillis)
        // After the audio: its end plus the clock since Stop.
        assertEquals(listOf(0L, 600_000L, 900_000L, 1_200_000L, 1_270_000L), path.points.map { it.atMillis })
        assertEquals(20f, path.topSpeedMps())
    }

    @Test
    fun thePathAfterStopEndsOnTime() {
        val m = MomentLocation(LocationSettings(savePath = true, keepAfterStop = true), stopOn = false, pathOn = true) { it }
        m.keepGoing(0, 0, KeepUntil.Minutes30, emptyList())
        assertFalse(m.keepingDone(29 * 60_000))
        assertTrue(m.keepingDone(30 * 60_000))
        assertFalse(MomentLocation(LocationSettings(), stopOn = false, pathOn = false) { it }.keepGoing(0, 0, KeepUntil.Hour1, emptyList()))
    }

    /** [n] points a second apart, heading north at 20 m/s. */
    private fun drive(n: Int) = MomentPath(
        (0 until n).map { i -> PathPoint(i * 1000L, north(i * 20.0), 4f, 20f, 0f, FixSource.Gps) },
        audioEndMillis = n * 1000L,
    )

    @Test
    fun thePathRidesInTheServerNoteCompactly() {
        val path = drive(3600) // an hour, a point a second
        val note = PathNoteV1.of(path)
        assertEquals(3600, note.t.size) // fits whole
        val back = note.toPath()
        assertEquals(path.audioEndMillis, back.audioEndMillis)
        path.points.zip(back.points).forEach { (a, b) ->
            assertEquals(a.atMillis, b.atMillis)
            assertTrue(abs(a.point.lat - b.point.lat) < 1e-6 && abs(a.point.lon - b.point.lon) < 1e-6)
            assertEquals(a.speedMps, b.speedMps)
        }
        val size = kotlinx.serialization.json.Json.encodeToString(note).length
        assertTrue(size < PathNoteV1.MAX_BYTES, "an hour takes $size bytes")
        // Ten hours don't fit whole: thinned, first and last kept.
        val long = PathNoteV1.of(drive(36_000))
        assertTrue(long.t.size < 36_000)
        assertTrue(kotlinx.serialization.json.Json.encodeToString(long).length <= PathNoteV1.MAX_BYTES)
        assertEquals(35_999_000L, long.toPath().points.last().atMillis)
    }

    @Test
    fun theServerNoteCarriesThePathOnlyWhenGiven() {
        val store = LectureStore(FakeFileSystem(), "/data".toPath())
        val lecture = Lecture("m", "מפגש", "he", 1, 60_000, emptyList(), emptyList(), hasPath = true)
        val without = store.serverNote(lecture)
        assertNull(store.pathFromServerNote(without))
        val with = store.serverNote(lecture, drive(10))
        assertEquals(10, store.pathFromServerNote(with)?.points?.size)
        assertEquals(lecture.copy(segments = emptyList()), store.fromServerNote(with))
    }
}
