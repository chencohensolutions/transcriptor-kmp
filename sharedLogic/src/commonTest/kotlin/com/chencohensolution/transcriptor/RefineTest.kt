package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Word
import com.chencohensolution.transcriptor.speech.Piece
import com.chencohensolution.transcriptor.speech.RefinePass
import com.chencohensolution.transcriptor.speech.SpeechEngine
import kotlin.test.Test
import kotlin.test.assertEquals

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
            "l", "nb", "t", "he", 0, 40_000,
            segments = listOf(
                Segment("s1", 1_000, 5_000, "חי", "turbo", speakerId = "speaker-1", words = listOf(Word(1_000, "חי"))),
                Segment("s2", 6_000, 9_000, "תוקן ביד", "turbo", edited = true),
                Segment("s3", 10_000, 10_400, "קצר", "turbo"),
            ),
            notes = emptyList(),
        )
        val asked = mutableListOf<Pair<Long, Long>>()
        val progress = mutableListOf<Float>()
        val refined = RefinePass(LargeModel()) { a, b -> asked += a to b; FloatArray(((b - a) * 16).toInt()) }
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
