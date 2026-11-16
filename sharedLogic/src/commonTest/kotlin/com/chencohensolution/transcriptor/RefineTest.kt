package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Word
import com.chencohensolution.transcriptor.speech.Piece
import com.chencohensolution.transcriptor.speech.RefinePass
import com.chencohensolution.transcriptor.speech.SpeechEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RefineTest {
    /** "בגדול: <n>" for a stretch of n ms; nothing (as Whisper on noise) for a stretch under a second. */
    private class LargeModel : SpeechEngine {
        override fun transcribe(samples: FloatArray, language: String, prompt: String?) =
            if (samples.size < 16_000) emptyList() else listOf(Piece(0, 0, " בגדול: ${samples.size / 16}. "))
        override fun close() {}
    }

    @Test
    fun linesAreTranscribedAgainKeepingTimesSpeakersAndEdits() {
        val lecture = Lecture(
            "l", "t", "he", 0, 40_000,
            segments = listOf(
                Segment("s1", 1_000, 5_000, "חי", "turbo", speakerId = "speaker-1", words = listOf(Word(1_000, "חי"))),
                Segment("s2", 6_000, 9_000, "תוקן ביד", "turbo", edited = true),
                Segment("s3", 10_000, 10_400, "קצר", "turbo"),
            ),
            notes = emptyList(),
        )
        val asked = mutableListOf<Pair<Long, Long>>()
        val progress = mutableListOf<Float>()
        val refined = RefinePass(LargeModel(), "he") { a, b -> asked += a to b; FloatArray(((b - a) * 16).toInt()) }
            .run(lecture, "large", progress::add)
        assertEquals(listOf("בגדול: 4500.", "תוקן ביד", "קצר"), refined.segments.map { it.text })
        assertEquals(listOf("large", "turbo", "turbo"), refined.segments.map { it.modelId })
        assertEquals(listOf(1_000L to 5_000L, 6_000L to 9_000L, 10_000L to 10_400L), refined.segments.map { it.startMillis to it.endMillis })
        assertEquals("speaker-1", refined.segments[0].speakerId)
        assertEquals(emptyList(), refined.segments[0].words)
        // The edited line isn't read again; the others with a little before and after.
        assertEquals(listOf(800L to 5_300L, 9_800L to 10_700L), asked)
        assertEquals(listOf(1f / 3, 2f / 3, 1f), progress)
        assertEquals("large", refined.refinedWith)
    }

    @Test
    fun aRecordingStartingStopsThePassBeforeTheNextLine() {
        val lecture = Lecture(
            "l", "t", "he", 0, 40_000,
            segments = listOf(Segment("s1", 1_000, 5_000, "א", "turbo"), Segment("s2", 6_000, 9_000, "ב", "turbo")),
            notes = emptyList(),
        )
        var asked = 0
        var recording = false
        val pass = RefinePass(LargeModel(), "he") { a, b -> asked++; recording = true; FloatArray(((b - a) * 16).toInt()) }
        // The first line is read, then a recording starts: the pass ends before the second, for its model to be closed.
        assertFailsWith<RefinePass.Stopped> { pass.run(lecture, "large", stop = { recording }) }
        assertEquals(1, asked)
    }

    /** Times its words: "מילה<t>" every second of the stretch, t its second in the stretch, said at t s + 0.5 s. */
    private class TimedModel : SpeechEngine {
        override fun transcribe(samples: FloatArray, language: String, prompt: String?) = error("not a line at a time")
        override val timesWords = true
        override fun transcribeWords(samples: FloatArray, language: String) =
            (0 until samples.size / 16_000).map { t -> Piece(t * 1_000L + 400, t * 1_000L + 600, " \u202Bמילה$t") }
        override fun close() {}
    }

    @Test
    fun linesThatFitAreImprovedTogetherAndGetTheirWordsBackByTime() {
        val lecture = Lecture(
            "l", "t", "he", 0, 60_000,
            segments = listOf(
                Segment("s1", 0, 2_000, "א", "live"),
                Segment("s2", 2_000, 4_000, "ב", "live"),
                Segment("s3", 4_000, 5_000, "ידני", "live", edited = true),
                Segment("s4", 5_000, 20_000, "ג", "live"),
                Segment("s5", 20_000, 40_000, "ד", "live"),
            ),
            notes = emptyList(),
        )
        val asked = mutableListOf<Pair<Long, Long>>()
        val progress = mutableListOf<Float>()
        val refined = RefinePass(TimedModel(), "he") { a, b -> asked += a to b; FloatArray(((b - a) * 16).toInt()) }
            .run(lecture, "large", progress::add)
        // s1 and s2 together (an edited line ends the window), s4 alone (s5 would make it longer than 28 s), s5 alone.
        assertEquals(listOf(0L to 4_300L, 4_800L to 20_300L, 19_800L to 40_300L), asked)
        // Words said at 0.5 s and 1.5 s are s1's, 2.5 s and 3.5 s s2's; without Whisper's direction marks.
        assertEquals("מילה0 מילה1", refined.segments[0].text)
        assertEquals("מילה2 מילה3", refined.segments[1].text)
        assertEquals("ידני", refined.segments[2].text)
        assertEquals("large", refined.segments[3].modelId)
        assertEquals(listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f), progress)
    }
}

class VersionTest {
    private val fs = okio.fakefilesystem.FakeFileSystem()
    private val store = com.chencohensolution.transcriptor.notebook.LectureStore(fs, okio.Path.Companion.run { "/data".toPath() })
    private val live = com.chencohensolution.transcriptor.lab.LabData.liveLectureWithSpeakers

    @Test
    fun aKeptTranscriptCanBeSwitchedBackAndForth() = kotlinx.coroutines.test.runTest {
        // Refined: the live transcript kept as a version first.
        val kept = store.keepCurrent(live, now = 100)
        val refined = kept.copy(segments = kept.segments.map { it.copy(text = it.text + "!", modelId = "large") }, refinedWith = "large")
        store.save(refined)
        assertEquals(listOf("v100"), refined.versions.map { it.id })
        assertEquals(live.segments, store.loadVersion(refined, "v100")!!.segments)

        val player = object : com.chencohensolution.transcriptor.review.AudioPlayer {
            override val positionMillis = kotlinx.coroutines.flow.MutableStateFlow(0L)
            override val playing = kotlinx.coroutines.flow.MutableStateFlow(false)
            override fun load(file: okio.Path) {}
            override fun load(stream: com.chencohensolution.transcriptor.review.AudioStream) {}
            override fun play() {}
            override fun pause() {}
            override fun seekTo(millis: Long) {}
            override fun setSpeed(speed: Float) {}
            override fun release() {}
        }
        var clock = 200L
        val review = com.chencohensolution.transcriptor.review.LectureReview(store, null, player, backgroundScope, now = { clock })
        review.open(live.id, "")
        review.switchTo("v100")
        val back = store.load(live.id)!!
        assertEquals(live.segments, back.segments)
        assertEquals(null, back.refinedWith)
        assertEquals(listOf("v200"), back.versions.map { it.id })
        assertEquals("large", back.versions.single().modelId)
        assertTrue(back.versions.single().refined)
        assertEquals(null, store.loadVersion(back, "v100"))

        clock = 300
        review.switchTo("v200")
        val again = store.load(live.id)!!
        assertEquals(refined.segments, again.segments)
        assertEquals("large", again.refinedWith)
        assertEquals(listOf("v300"), again.versions.map { it.id })
    }
}
