package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.recording.LectureSession
import com.chencohensolution.transcriptor.recording.LiveModel
import com.chencohensolution.transcriptor.settings.SettingsStore
import com.chencohensolution.transcriptor.speech.SpeakerLabeler
import com.chencohensolution.transcriptor.speech.SpeakerTracker
import com.chencohensolution.transcriptor.speech.UtteranceTranscriber
import com.chencohensolution.transcriptor.speech.Voice
import com.chencohensolution.transcriptor.speech.VoicePrintThresholds
import com.chencohensolution.transcriptor.models.ModelCatalog
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakerTest {
    @Test
    fun alikePrintsAreOneSpeakerAndADifferentOneIsNew() {
        val t = SpeakerTracker()
        assertEquals("speaker-1", t.assign(floatArrayOf(1f, 0.1f)))
        assertEquals("speaker-1", t.assign(floatArrayOf(0.9f, 0.2f)))
        assertEquals("speaker-2", t.assign(floatArrayOf(0.1f, 1f)))
        assertEquals(listOf(Speaker("speaker-1", ""), Speaker("speaker-2", "")), t.speakers)
        assertNull(t.print("speaker-3"))
    }

    @Test
    fun aKnownVoiceIsNamedOnceItSpeaks() {
        val t = SpeakerTracker(listOf(Voice("המרצה", listOf(1f, 0f))))
        assertTrue(t.speakers.isEmpty())
        assertEquals("speaker-1", t.assign(floatArrayOf(0.1f, 1f)))
        assertEquals("speaker-2", t.assign(floatArrayOf(1f, 0.1f)))
        assertEquals(listOf(Speaker("speaker-1", ""), Speaker("speaker-2", "המרצה")), t.speakers)
    }

    @Test
    fun windowsAreGroupedWithoutChangingTheSpeakers() {
        val t = SpeakerTracker()
        t.assign(floatArrayOf(1f, 0.1f))
        val a = floatArrayOf(0.9f, 0.1f)
        val b = floatArrayOf(0.1f, 0.9f)
        assertEquals(listOf(0, 0, 1, 1, 0), t.group(listOf(a, a, b, b, a)))
        assertEquals(listOf(Speaker("speaker-1", "")), t.speakers)
    }

    @Test
    fun aShortLineGetsNoLabel() {
        val labeler = SpeakerLabeler(SpeakerTracker()) { PitchEmbedder() }
        assertNull(labeler.label(FloatArray(SpeakerLabeler.MIN_SAMPLES - 1)))
        assertNotNull(labeler.label(FloatArray(SpeakerLabeler.MIN_SAMPLES) { if (it % 40 < 20) 0.3f else -0.3f }))
    }

    private val fs = FakeFileSystem().apply { allowReadsWhileWriting = true; allowWritesWhileWriting = true }
    private val root = "/data".toPath()
    private val store = LectureStore(fs, root)
    private val voices = VoiceStore(fs, root)
    private val notebook = Notebook("psych", "פסיכולוגיה", "he", emptyList())

    private fun TestScope.session(recorder: FakeRecorder) =
        LectureSession(recorder, store, SettingsStore(fs, root), backgroundScope, now = { testScheduler.currentTime + 1 }, voices = voices) { language, scope, known ->
            val tracker = SpeakerTracker(known)
            LiveModel(
                "fake",
                UtteranceTranscriber(language, "m", scope, StandardTestDispatcher(testScheduler), SpeakerLabeler(tracker) { PitchEmbedder() }) { FakeEngine() },
                tracker,
            )
        }

    private fun FakeRecorder.say(millis: Long, hertz: Int) {
        advance(millis, tone(millis, hertz = hertz))
        advance(2_200, silence(2_200))
    }

    @Test
    fun linesGetSpeakersAndANameCarriesToTheNextLecture() = runTest {
        val recorder = FakeRecorder(fs)
        val first = session(recorder)
        first.start(notebook, "1")
        recorder.say(3_000, 300)
        recorder.say(3_000, 800)
        recorder.say(3_000, 300)
        runCurrent()
        val lines = first.state.value!!.lecture.segments
        assertEquals(listOf("speaker-1", "speaker-2", "speaker-1"), lines.map { it.speakerId })
        first.renameSpeaker("speaker-1", "המרצה")
        runCurrent()
        assertEquals("המרצה", first.state.value!!.lecture.speakers.first { it.id == "speaker-1" }.name)
        val stopped = assertNotNull(first.stop())
        assertEquals(listOf(Speaker("speaker-1", "המרצה"), Speaker("speaker-2", "")), stopped.speakers)
        assertEquals(listOf("המרצה"), voices.load("psych").map { it.name })

        // The next lecture: the student speaks first, then the lecturer, who is named from the start.
        val recorder2 = FakeRecorder(fs)
        val second = session(recorder2)
        testScheduler.advanceTimeBy(1_000)
        second.start(notebook, "2")
        recorder2.say(3_000, 800)
        recorder2.say(3_000, 300)
        runCurrent()
        val lecture = second.state.value!!.lecture
        assertEquals(listOf("speaker-1", "speaker-2"), lecture.segments.map { it.speakerId })
        assertEquals(listOf(Speaker("speaker-1", ""), Speaker("speaker-2", "המרצה")), lecture.speakers)
    }

    @Test
    fun voicesKeepAPrintPerModelAndNeverMixThem() {
        val fs = FakeFileSystem()
        var model = "cam"
        val store = VoiceStore(fs, "/v".toPath()) { model }
        store.save("nb", Voice("המרצה", listOf(1f, 0f)))
        model = "resnet"
        assertEquals(emptyList(), store.load("nb"))
        store.save("nb", Voice("המרצה", listOf(0f, 1f, 0f)))
        assertEquals(listOf(listOf(0f, 1f, 0f)), store.load("nb").map { it.print })
        store.rename("nb", "המרצה", "Dana")
        assertEquals(listOf("Dana"), store.load("nb").map { it.name })
        model = "cam"
        assertEquals(listOf(Voice("Dana", listOf(1f, 0f), "cam")), store.load("nb"))
        // A print made by a lecture's tracker keeps its model, whichever model is picked meanwhile.
        store.save("nb", Voice("Noa", listOf(0f, 0f, 1f), "resnet"))
        assertEquals(listOf("Dana"), store.load("nb").map { it.name })
    }

    @Test
    fun voicesSavedBeforeModelsHadIdsAreCamPlusPlus() {
        val fs = FakeFileSystem()
        val file = "/v/notebooks/nb/voices.json".toPath()
        fs.createDirectories(file.parent!!)
        fs.write(file) { writeUtf8("""{"v":1,"voices":[{"name":"המרצה","print":[1.0,0.0]}]}""") }
        assertEquals(listOf(ModelCatalog.voicePrints.id), VoiceStore(fs, "/v".toPath()).load("nb").map { it.model })
        assertEquals(emptyList(), VoiceStore(fs, "/v".toPath()) { "other" }.load("nb"))
    }

    @Test
    fun aTrackerComparesOnlyPrintsOfItsModel() {
        val t = SpeakerTracker(listOf(Voice("המרצה", listOf(1f, 0f), "cam"), Voice("Dana", listOf(1f, 0f), "resnet")), model = "resnet")
        t.assign(floatArrayOf(1f, 0.1f))
        assertEquals(listOf(Speaker("speaker-1", "Dana")), t.speakers)
    }

    @Test
    fun eachVoiceModelHasItsThresholds() {
        val cam = VoicePrintThresholds.of(ModelCatalog.voicePrints.id)
        assertEquals(SpeakerTracker.THRESHOLD, cam.sameSpeaker)
        assertEquals(cam, VoicePrintThresholds.of("unknown"))
        for (m in ModelCatalog.voicePrintModels) assertTrue(VoicePrintThresholds.of(m.id).sameSpeaker in 0.2f..0.9f, m.id)
    }

    @Test
    fun theScreenShowsTheVoicePrintModelAndWhereItRuns() = runTest {
        val recorder = FakeRecorder(fs)
        val inUse = kotlinx.coroutines.flow.MutableStateFlow(com.chencohensolution.transcriptor.recording.ModelInUse("ResNet100"))
        val settings = SettingsStore(fs, root).apply { findSpeakersOnStop = true }
        val session = LectureSession(
            recorder, store, settings, backgroundScope, now = { testScheduler.currentTime + 1 }, voices = voices,
            speakerPassReady = { true },
        ) { language, scope, known ->
            val tracker = SpeakerTracker(known)
            val labeler = SpeakerLabeler(tracker) {
                PitchEmbedder().also { inUse.value = inUse.value.copy(runs = com.chencohensolution.transcriptor.recording.ModelInUse.Runs.Npu) }
            }
            LiveModel("fake", UtteranceTranscriber(language, "m", scope, StandardTestDispatcher(testScheduler), labeler) { FakeEngine() }, tracker, inUse)
        }
        session.start(notebook, "1")
        runCurrent()
        assertEquals(com.chencohensolution.transcriptor.recording.ModelInUse.Runs.NotYet, session.state.value!!.voicePrints!!.runs)
        assertTrue(session.state.value!!.speakerPassOnStop)
        recorder.say(3_000, 300)
        runCurrent()
        assertEquals(com.chencohensolution.transcriptor.recording.ModelInUse("ResNet100", com.chencohensolution.transcriptor.recording.ModelInUse.Runs.Npu), session.state.value!!.voicePrints)
        session.stop()
    }

    @Test
    fun moreSpeakersRaisesTheCosinesAndLowersTheClustering() {
        val id = ModelCatalog.voicePrintsResNet293.id
        val tuned = VoicePrintThresholds.of(id)
        assertEquals(tuned, VoicePrintThresholds.of(id, 0f))
        val more = VoicePrintThresholds.of(id, 1f)
        val fewer = VoicePrintThresholds.of(id, -1f)
        assertTrue(more.live > tuned.live && tuned.live > fewer.live)
        assertTrue(more.sameSpeaker > tuned.sameSpeaker && more.clustering < tuned.clustering && fewer.clustering > tuned.clustering)
        assertEquals(more, VoicePrintThresholds.of(id, 5f))
        assertTrue(fewer.live > 0f)
    }

    @Test
    fun theSpeakerTuningIsKeptPerModel() {
        val settings = SettingsStore(FakeFileSystem(), "/s".toPath())
        assertEquals(0f, settings.speakerTuning("cam"))
        settings.setSpeakerTuning("cam", 0.5f)
        settings.setSpeakerTuning("resnet", -1f)
        assertEquals(0.5f, settings.speakerTuning("cam"))
        assertEquals(-1f, settings.speakerTuning("resnet"))
        settings.setSpeakerTuning("cam", 0f)
        assertEquals(0f, settings.speakerTuning("cam"))
    }
}
