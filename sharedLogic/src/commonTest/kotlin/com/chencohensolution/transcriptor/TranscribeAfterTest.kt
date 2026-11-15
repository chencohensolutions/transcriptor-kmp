package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.recording.LectureSession
import com.chencohensolution.transcriptor.recording.LiveModel
import com.chencohensolution.transcriptor.settings.SettingsStore
import com.chencohensolution.transcriptor.speech.AudioSource
import com.chencohensolution.transcriptor.speech.Downsampler
import com.chencohensolution.transcriptor.speech.Piece
import com.chencohensolution.transcriptor.speech.RefinePass
import com.chencohensolution.transcriptor.speech.SpeechEngine
import com.chencohensolution.transcriptor.speech.UtteranceTranscriber
import com.chencohensolution.transcriptor.speech.lineTimes
import com.chencohensolution.transcriptor.speech.transcribed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Record now, transcribe after: a moment recorded without live transcription, then its lines found and written. */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscribeAfterTest {
    /** A recording as the passes read it (16 kHz mono), handed out in small pieces. */
    private fun source(vararg pcm: ByteArray): AudioSource {
        val down = Downsampler()
        val all = pcm.map { down.process(it, it.size) }.fold(FloatArray(0)) { a, b -> a + b }
        var at = 0
        return AudioSource { max ->
            if (at >= all.size) null else all.copyOfRange(at, minOf(all.size, at + minOf(max, 7_000))).also { at += it.size }
        }
    }

    /** "<n> ms" for a stretch of n ms; nothing under three and a half seconds (as Whisper on a noise). */
    private class Model : SpeechEngine {
        override fun transcribe(samples: FloatArray, language: String, prompt: String?) =
            if (samples.size < 56_000) emptyList() else listOf(Piece(0, 0, " אמר ${samples.size / 16}. "))
        override fun close() {}
    }

    @Test
    fun theLinesOfARecordingAreFoundAtItsPauses() {
        val read = mutableListOf<Long>()
        val lines = lineTimes(source(silence(1_000), tone(4_000), silence(3_000), tone(5_000), silence(3_000), tone(2_000)), progress = read::add)
        assertEquals(3, lines.size)
        assertEquals(listOf("s1", "s2", "s3"), lines.map { it.id })
        assertTrue(lines.all { it.text.isEmpty() })
        // Each line starts a little before its speech and ends in the quiet after it; the last at the end of the audio.
        assertTrue(lines[0].startMillis in 600..1_000 && lines[0].endMillis in 5_000..6_000, "${lines[0]}")
        assertTrue(lines[1].startMillis in 7_600..8_000 && lines[1].endMillis in 13_000..14_000, "${lines[1]}")
        assertTrue(lines[2].startMillis in 15_600..16_000 && lines[2].endMillis in 17_900..18_100, "${lines[2]}")
        assertEquals(18_000, read.last())
        assertTrue(read.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun aQuietRecordingHasNoLinesAndStoppingEndsEarly() {
        assertEquals(emptyList(), lineTimes(source(silence(20_000))))
        var asked = 0
        val lines = lineTimes(source(tone(4_000), silence(3_000), tone(4_000), silence(3_000)), stop = { asked++ >= 1 })
        assertTrue(lines.size <= 1)
    }

    @Test
    fun aRecordedMomentGetsItsLinesFromTheImproveModelAndKeepsItsNotes() {
        val recorded = Lecture(
            "l", "Sat dinner", "he", 0, 20_000, segments = emptyList(), notes = listOf(Note("n1", 3_000, "לזכור")),
            labels = listOf("friends"), pendingTranscript = true,
        )
        val lines = lineTimes(source(silence(1_000), tone(4_000), silence(3_000), tone(600), silence(3_000), tone(5_000), silence(3_400)))
        assertEquals(3, lines.size, lines.toString())
        val pass = RefinePass(Model(), "he") { a, b -> FloatArray(((b - a) * 16).toInt()) }
        val done = transcribed(recorded, lines, "large") { pass.run(it, "large") }
        // The short noise the model wrote nothing for is not a line.
        assertEquals(2, done.segments.size)
        assertEquals(listOf("s1", "s2"), done.segments.map { it.id })
        assertTrue(done.segments.all { it.text.startsWith("אמר") && it.modelId == "large" })
        assertEquals(lines[0].startMillis, done.segments[0].startMillis)
        assertEquals(lines[2].startMillis, done.segments[1].startMillis)
        assertFalse(done.pendingTranscript)
        assertEquals("large", done.refinedWith)
        assertEquals(recorded.notes, done.notes)
        assertEquals(listOf("friends"), done.labels)
    }

    private val fs = FakeFileSystem()
    private val root = "/files".toPath()
    private val store = LectureStore(fs, root)
    private val recorder = FakeRecorder(fs)

    @Test
    fun afterIsTheDefaultAndIsKept() {
        val settings = SettingsStore(fs, root)
        assertFalse(settings.recordLive)
        settings.recordLive = true
        assertTrue(SettingsStore(fs, root).recordLive)
    }

    @Test
    fun aMomentRecordedWithoutLiveWaitsForItsTranscript() = runTest {
        var asked = 0
        val session = LectureSession(recorder, store, SettingsStore(fs, root), backgroundScope, now = { 1L }) { language, scope, _ ->
            asked++
            LiveModel("fake", UtteranceTranscriber(language, "m", scope, StandardTestDispatcher(testScheduler)) { Model() })
        }
        val lecture = session.start(listOf("friends"), "Friends", "en", "t", transcribeLive = false)
        runCurrent()
        // The live model isn't loaded at all, and the file says so from the first save.
        assertEquals(0, asked)
        assertEquals(true, session.state.value?.recordOnly)
        assertNull(session.state.value?.liveModelName)
        assertEquals(true, store.load(lecture.id)?.pendingTranscript)
        recorder.advance(4_000, tone(4_000))
        session.addNote(2_000, "mark")
        val stopped = assertNotNull(session.stop())
        assertTrue(stopped.pendingTranscript)
        assertEquals(emptyList(), stopped.segments)
        assertEquals(1, stopped.notes.size)
        val saved = assertNotNull(store.load(lecture.id))
        assertTrue(saved.pendingTranscript)
        assertEquals(4_000, saved.durationMillis)
    }

    @Test
    fun liveWithNoModelForTheLanguageIsRecordedAndWaitsToo() = runTest {
        val session = LectureSession(recorder, store, SettingsStore(fs, root), backgroundScope, now = { 2L })
        val lecture = session.start(emptyList(), "", "en", "t", transcribeLive = true)
        runCurrent()
        assertEquals(true, session.state.value?.recordOnly)
        recorder.advance(1_000, tone(1_000))
        assertTrue(assertNotNull(session.stop()).pendingTranscript)
        assertEquals(true, store.load(lecture.id)?.pendingTranscript)
    }

    @Test
    fun aMomentTranscribedLiveDoesNotWait() = runTest {
        val session = LectureSession(recorder, store, SettingsStore(fs, root), backgroundScope, now = { 3L }) { language, scope, _ ->
            LiveModel("fake", UtteranceTranscriber(language, "m", scope, StandardTestDispatcher(testScheduler)) { Model() })
        }
        val lecture = session.start(emptyList(), "", "he", "t")
        runCurrent()
        assertEquals(false, session.state.value?.recordOnly)
        assertEquals(false, store.load(lecture.id)?.pendingTranscript)
        assertFalse(assertNotNull(session.stop()).pendingTranscript)
    }

    @Test
    fun aMarkIsANoteWithoutWordsAtThisSecond() = runTest {
        val session = LectureSession(recorder, store, SettingsStore(fs, root), backgroundScope, now = { 4L })
        assertNull(session.mark())
        val lecture = session.start(emptyList(), "", "en", "t", transcribeLive = false)
        recorder.advance(7_000, tone(7_000))
        assertEquals(7_000L, session.mark())
        session.addNote(8_000, "the boat")
        recorder.advance(2_000, tone(2_000))
        assertEquals(9_000L, session.mark())
        runCurrent()
        val notes = assertNotNull(store.load(lecture.id)).notes
        assertEquals(listOf("n1" to true, "n2" to false, "n3" to true), notes.map { it.id to it.isMark })
        assertEquals(listOf(7_000L, 8_000L, 9_000L), notes.map { it.atMillis })
        val stopped = assertNotNull(session.stop())
        assertEquals(3, stopped.notes.size)
        // The transcript beside the audio shows a mark by its time alone.
        val text = com.chencohensolution.transcriptor.notebook.transcriptMarkdown(stopped)
        assertTrue("⚑ 0:07" in text, text)
        assertTrue("0:08 — the boat" in text, text)
    }
}
