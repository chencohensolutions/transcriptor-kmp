package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Word
import com.chencohensolution.transcriptor.speech.LiveSpeakers
import com.chencohensolution.transcriptor.speech.LiveTurn
import com.chencohensolution.transcriptor.speech.Segmentation
import com.chencohensolution.transcriptor.speech.Segmenter
import com.chencohensolution.transcriptor.speech.SpeakerTracker
import com.chencohensolution.transcriptor.speech.Voice
import com.chencohensolution.transcriptor.speech.liveLines
import com.chencohensolution.transcriptor.speech.powersetToActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tone at 16 kHz, -1..1. */
private fun tone16k(millis: Int, hertz: Int) = FloatArray(millis * 16) { (0.3 * sin(2 * PI * hertz * it / 16_000)).toFloat() }

/**
 * A segmentation model that hears pitch: per 10 ms frame, a low voice and a high one (zero crossings), numbered by
 * the window, swapped in every other window as pyannote's local numbering may be.
 */
private class PitchSegmenter : Segmenter {
    override val windowSize = 160_000
    var calls = 0
    override fun segment(window: FloatArray): Segmentation {
        val swap = calls++ % 2 == 1
        val active = (0 until window.size / 160).map { f ->
            val a = f * 160
            var loud = false
            var crossings = 0
            for (i in a + 1 until a + 160) {
                if (abs(window[i]) > 0.05f) loud = true
                if ((window[i - 1] < 0) != (window[i] < 0)) crossings++
            }
            val high = crossings * 100 / 2 > 550
            BooleanArray(3) { k -> loud && k == (if (high != swap) 1 else 0) }
        }
        return Segmentation(160, 160, active)
    }

    override fun close() {}
}

class LiveSpeakersTest {
    @Test
    fun powersetClassesBecomeSpeakers() {
        // Frames: silence, speaker 1 alone, speakers 0 and 2 together.
        val logProbs = floatArrayOf(
            0f, -5f, -5f, -5f, -5f, -5f, -5f,
            -5f, -5f, 0f, -5f, -5f, -5f, -5f,
            -5f, -5f, -5f, -5f, -5f, 0f, -5f,
        )
        val active = powersetToActive(logProbs, 7)
        assertContentEquals(booleanArrayOf(false, false, false), active[0])
        assertContentEquals(booleanArrayOf(false, true, false), active[1])
        assertContentEquals(booleanArrayOf(true, false, true), active[2])
    }

    @Test
    fun voicesAreFollowedAcrossWindowsAndSettledOnce() = runTest {
        val segmenter = PitchSegmenter()
        val embedder = PitchEmbedder()
        val tracker = SpeakerTracker()
        val live = LiveSpeakers(tracker, backgroundScope, StandardTestDispatcher(testScheduler), { segmenter }, { embedder })
        for ((millis, hertz) in listOf(6_000 to 300, 6_000 to 800, 6_000 to 300)) live.feed16k(tone16k(millis, hertz))
        live.finish()
        val state = live.state.value
        assertEquals(18_000, state.settledMillis)
        // Each moment settled once: the turns of a speaker don't overlap each other.
        for ((_, own) in state.turns.groupBy { it.speaker }) {
            own.zipWithNext().forEach { (a, b) -> assertTrue(a.endMillis <= b.startMillis, "$a overlaps $b") }
        }
        fun who(at: Long) = state.turns.firstOrNull { at in it.startMillis until it.endMillis }?.speaker
        assertEquals("speaker-1", who(1_000))
        assertEquals("speaker-2", who(9_000))
        assertEquals("speaker-1", who(15_000))
        assertEquals(2, tracker.speakers.size)
        assertTrue(embedder.closed)
    }

    @Test
    fun aKnownVoiceIsNamedLive() = runTest {
        val tracker = SpeakerTracker(listOf(Voice("המרצה", listOf(0.1f, 1f))))
        val live = LiveSpeakers(tracker, backgroundScope, StandardTestDispatcher(testScheduler), { PitchSegmenter() }, { PitchEmbedder() })
        live.feed16k(tone16k(4_000, 800))
        live.finish()
        assertEquals("המרצה", tracker.speakers.single().name)
    }

    @Test
    fun linesSplitWhereSettledTurnsChangeAndWaitOtherwise() {
        val words = listOf(Word(0, "שלום"), Word(800, "לכולם"), Word(1_600, "שאלה"), Word(2_400, "קטנה"))
        val line = Segment("s1", 0, 3_000, "שלום לכולם שאלה קטנה", "m", words = words)
        val later = Segment("s2", 4_000, 6_000, "עוד", "m")
        val turns = listOf(LiveTurn(0, 1_500, "speaker-1"), LiveTurn(1_500, 3_000, "speaker-2"))
        val out = liveLines(listOf(line, later), turns, settledMillis = 3_500)
        assertEquals(listOf("s1", "s1.2", "s2"), out.map { it.id })
        assertEquals(listOf("speaker-1", "speaker-2", null), out.map { it.speakerId })
        assertEquals(listOf("שלום לכולם", "שאלה קטנה", "עוד"), out.map { it.text })
    }
}

class SpeakerTuningTest {
    /** Two voices, A near (1, 0) and B near (0, 1); live gave A's second half a speaker of its own. */
    private val stretches = listOf(
        com.chencohensolution.transcriptor.speech.LiveStretch(0, 2_000, "speaker-1", listOf(1f, 0.05f)),
        com.chencohensolution.transcriptor.speech.LiveStretch(2_000, 4_000, "speaker-2", listOf(0.05f, 1f)),
        com.chencohensolution.transcriptor.speech.LiveStretch(4_000, 6_000, "speaker-3", listOf(0.8f, 0.45f)),
        com.chencohensolution.transcriptor.speech.LiveStretch(6_000, 7_000, "speaker-3", follows = 2),
    )
    private fun line(id: String, a: Long, b: Long, who: String) = Segment(id, a, b, "x", "m", speakerId = who)

    @Test
    fun theUsersMergeIsACorrectionAndFewerSpeakersMatchIt() {
        // The user merged speaker-3 into speaker-1.
        val lines = listOf(line("s1", 0, 2_000, "speaker-1"), line("s2", 2_000, 4_000, "speaker-2"), line("s3", 4_000, 7_000, "speaker-1"))
        assertEquals(1, com.chencohensolution.transcriptor.speech.SpeakerTuning.corrections(lines, stretches))
        // Tuned at a cosine of 0.9: speaker-3 stays apart (its cosine with A is about 0.87).
        val r = com.chencohensolution.transcriptor.speech.SpeakerTuning.tune(lines, stretches, tuned = 0.9f, now = 0f)!!
        assertEquals(2, r.matchesNow)
        assertEquals(3, r.matches)
        assertEquals(3, r.speakersNow)
        assertEquals(2, r.speakers)
        assertTrue(r.more < 0f, "fewer speakers")
    }

    @Test
    fun followingStretchesTakeTheirSpeakerInReplay() {
        val replayed = com.chencohensolution.transcriptor.speech.replayStretches(stretches, 0.5f)
        assertEquals(replayed[2], replayed[3])
        assertEquals(replayed[0], replayed[2])
    }

    @Test
    fun liveStretchesAreKeptWithTheLecture() {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val store = com.chencohensolution.transcriptor.notebook.LectureStore(fs, okio.Path.Companion.run { "/d".toPath() })
        val lecture = com.chencohensolution.transcriptor.notebook.Lecture(
            id = "l1", notebookId = "nb", title = "t", language = "he", startedAtMillis = 0, durationMillis = 7_000,
            segments = emptyList(), notes = emptyList(),
        )
        store.save(lecture)
        store.saveLiveSpeakers(lecture, "resnet", stretches)
        assertEquals("resnet" to stretches, store.loadLiveSpeakers(lecture))
    }
}
