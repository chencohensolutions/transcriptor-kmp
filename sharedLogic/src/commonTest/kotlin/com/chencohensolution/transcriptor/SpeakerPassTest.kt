package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.Word
import com.chencohensolution.transcriptor.speech.AudioSource
import com.chencohensolution.transcriptor.speech.Diarizer
import com.chencohensolution.transcriptor.speech.Downsampler
import com.chencohensolution.transcriptor.speech.SpeakerPass
import com.chencohensolution.transcriptor.speech.Turn
import com.chencohensolution.transcriptor.speech.Voice
import com.chencohensolution.transcriptor.speech.withTurns
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Turns from loudness and pitch, a second at a time: what a real diarizer finds in the test tones. */
private class PitchDiarizer : Diarizer {
    val calls = mutableListOf<Int>()
    override fun diarize(samples: FloatArray): List<Turn> {
        calls += samples.size
        val turns = mutableListOf<Turn>()
        for (s in 0 until samples.size / 16_000) {
            val second = samples.copyOfRange(s * 16_000, (s + 1) * 16_000)
            if (second.maxOf { abs(it) } < 0.05f) continue
            val pitch = PitchEmbedder().embed(second)
            val speaker = if (pitch[0] > pitch[1]) "0" else "1"
            val last = turns.lastOrNull()
            if (last != null && last.speaker == speaker && last.endMillis == s * 1000L) turns[turns.lastIndex] = last.copy(endMillis = (s + 1) * 1000L)
            else turns += Turn(s * 1000L, (s + 1) * 1000L, speaker)
        }
        return turns
    }
}

private class Pieces(pcm: ByteArray) : AudioSource {
    private val all = Downsampler().process(pcm, pcm.size)
    private var at = 0
    override fun read(maxSamples: Int): FloatArray? {
        if (at >= all.size) return null
        return all.copyOfRange(at, minOf(all.size, at + maxSamples)).also { at += it.size }
    }
}

class SpeakerPassTest {
    @Test
    fun turnsAcrossPiecesKeepOneIdPerVoiceAndKnownNames() {
        val diarizer = PitchDiarizer()
        val pass = SpeakerPass(diarizer, PitchEmbedder())
        // Over 5 minutes, so it comes in two pieces: the lecturer in both, a student in the second.
        val pcm = tone(200_000, hertz = 300) + silence(20_000) + tone(60_000, hertz = 300) + tone(30_000, hertz = 800) + silence(5_000)
        val result = pass.run(Pieces(pcm), listOf(Voice("המרצה", listOf(1f, 0.1f))))
        assertEquals(2, diarizer.calls.size)
        assertEquals(listOf(Speaker("speaker-1", "המרצה"), Speaker("speaker-2", "")), result.speakers)
        assertEquals(setOf("speaker-1"), result.turns.filter { it.endMillis <= 280_000 }.map { it.speaker }.toSet())
        // The student from 280 s, across the pieces' border at 300 s.
        val student = result.turns.filter { it.speaker == "speaker-2" }
        assertTrue(student.first().startMillis in 279_000L..281_000L, student.toString())
        assertTrue(student.last().endMillis in 309_000L..311_000L, student.toString())
    }

    private fun words(vararg at: Pair<Long, String>) = at.map { Word(it.first, it.second) }

    @Test
    fun aLineIsSplitWhereTheTurnChanges() {
        val lecture = Lecture(
            "l", "t", "he", 0, 20_000,
            segments = listOf(
                Segment("s1", 1_000, 9_000, "אחת שתיים שלוש ארבע חמש", "z", speakerId = "speaker-1",
                    words = words(1_000L to "אחת", 2_000L to "שתיים", 4_500L to "שלוש", 6_000L to "ארבע", 7_000L to "חמש")),
                Segment("s2", 10_000, 12_000, "בלי מילים", "w"),
            ),
            notes = emptyList(),
        )
        val turns = listOf(Turn(0, 4_000, "speaker-1"), Turn(4_000, 9_500, "speaker-2"), Turn(9_500, 12_500, "speaker-1"))
        val after = lecture.withTurns(turns, listOf(Speaker("speaker-1", "המרצה"), Speaker("speaker-2", "")))
        assertEquals(listOf("אחת שתיים", "שלוש ארבע חמש", "בלי מילים"), after.segments.map { it.text })
        assertEquals(listOf("speaker-1", "speaker-2", "speaker-1"), after.segments.map { it.speakerId })
        assertEquals(listOf(1_000L to 4_500L, 4_500L to 9_000L, 10_000L to 12_000L), after.segments.map { it.startMillis to it.endMillis })
        assertEquals(listOf("s1", "s2", "s3"), after.segments.map { it.id })
        assertTrue(after.speakerPass)
    }

    @Test
    fun oneWordOfAnotherSpeakerStaysInTheLine() {
        val lecture = Lecture(
            "l", "t", "he", 0, 10_000,
            segments = listOf(Segment("s1", 0, 6_000, "א ב ג ד", "z", words = words(500L to "א", 1_500L to "ב", 3_000L to "ג", 4_500L to "ד"))),
            notes = emptyList(),
        )
        val turns = listOf(Turn(0, 2_500, "speaker-1"), Turn(2_500, 3_500, "speaker-2"), Turn(3_500, 6_000, "speaker-1"))
        val after = lecture.withTurns(turns, listOf(Speaker("speaker-1", ""), Speaker("speaker-2", "")))
        assertEquals(listOf("א ב ג ד"), after.segments.map { it.text })
        assertEquals(listOf("speaker-1"), after.segments.map { it.speakerId })
        assertEquals(listOf(Speaker("speaker-1", "")), after.speakers)
    }

    @Test
    fun anEditedLineKeepsItsTextAndGetsTheMainSpeaker() {
        val lecture = Lecture(
            "l", "t", "he", 0, 10_000,
            segments = listOf(Segment("s1", 0, 6_000, "תוקן ביד", "z", edited = true, words = words(500L to "א", 4_000L to "ב"))),
            notes = emptyList(),
        )
        val after = lecture.withTurns(listOf(Turn(0, 1_000, "speaker-1"), Turn(1_000, 6_000, "speaker-2")), listOf(Speaker("speaker-1", ""), Speaker("speaker-2", "")))
        assertEquals(listOf("תוקן ביד" to "speaker-2"), after.segments.map { it.text to it.speakerId })
    }
}
