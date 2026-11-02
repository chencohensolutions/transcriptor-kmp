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
}
