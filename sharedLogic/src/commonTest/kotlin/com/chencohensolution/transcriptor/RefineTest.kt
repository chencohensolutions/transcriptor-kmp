package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Word
import com.chencohensolution.transcriptor.speech.Piece
import com.chencohensolution.transcriptor.speech.RefinePass
import com.chencohensolution.transcriptor.speech.SpeechEngine
import kotlin.test.Test
import kotlin.test.assertEquals
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
