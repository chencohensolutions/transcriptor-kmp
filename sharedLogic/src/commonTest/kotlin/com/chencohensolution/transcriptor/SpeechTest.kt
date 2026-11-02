package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.speech.Downsampler
import com.chencohensolution.transcriptor.speech.LiveTranscriber
import com.chencohensolution.transcriptor.speech.Piece
import com.chencohensolution.transcriptor.speech.SpeechEngine
import com.chencohensolution.transcriptor.speech.Utterances
import com.chencohensolution.transcriptor.speech.cleanLine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Says "line N" for the Nth call, and records what it was given. */
class FakeEngine : SpeechEngine {
    val calls = mutableListOf<Int>()
    val prompts = mutableListOf<String?>()
    var closed = false
    override fun transcribe(samples: FloatArray, language: String, prompt: String?): List<Piece> {
        calls += samples.size
        prompts += prompt
        return listOf(Piece(0, samples.size / 16L, " line ${calls.size} "))
    }
    override fun close() { closed = true }
}

class SpeechTest {
    @Test
    fun downsamplingGivesAThirdOfTheFramesInMono() {
        val d = Downsampler()
        val out = d.process(tone(1_000), tone(1_000).size)
        assertEquals(16_000, out.size)
        // 0.3 of full scale survives the filter (300 Hz is far below the cut-off).
        assertTrue(abs(out.drop(100).maxOrNull()!! - 0.3f) < 0.02f)
        // Odd-sized pieces line up with one whole buffer.
        val e = Downsampler()
        val pcm = tone(100)
        val parts = e.process(pcm.copyOfRange(0, 4 * 1000), 4 * 1000).size + e.process(pcm.copyOfRange(4 * 1000, pcm.size), pcm.size - 4 * 1000).size
        assertEquals(1_600, parts)
    }

    private fun mono(pcm: ByteArray) = Downsampler().process(pcm, pcm.size)

    @Test
    fun aPauseEndsALineWithItsTimes() {
        val u = Utterances()
        val closed = u.feed(mono(silence(1_000) + tone(3_500) + silence(1_000)))
        assertEquals(1, closed.size)
        // Starts a little before the speech (pre-roll), ends after the pause that closed it.
        assertTrue(closed[0].startSample / 16 in 700L..1_000L, "start ${closed[0].startSample / 16}")
        assertTrue(closed[0].endSample / 16 in 5_050L..5_500L, "end ${closed[0].endSample / 16}")
    }

    @Test
    fun aShortLineWaitsForALongerPause() {
        val u = Utterances()
        // 1.5 s of speech, a 0.8 s pause, more speech: one line, not two.
        assertTrue(u.feed(mono(silence(500) + tone(1_500) + silence(800) + tone(2_000))).isEmpty())
        assertEquals(1, u.feed(mono(silence(800))).size)
    }

    @Test
    fun tooManyWordsForTheTimeIsDropped() = runTest {
        val engine = object : SpeechEngine {
            override fun transcribe(samples: FloatArray, language: String, prompt: String?) =
                listOf(Piece(0, 1000, "חבר הכנסת איילתא מיוחדת, ".repeat(5) + "חבר הכנסת גדול"))
            override fun close() {}
        }
        val live = LiveTranscriber("he", "m", backgroundScope, StandardTestDispatcher(testScheduler)) { engine }
        val pcm = silence(300) + tone(1_200) + silence(2_200)
        live.feed(pcm, pcm.size)
        runCurrent()
        assertTrue(live.output.value.segments.isEmpty())
    }

    @Test
    fun aShortSoundIsNotALine() {
        val u = Utterances()
        assertTrue(u.feed(mono(silence(500) + tone(120) + silence(1_000))).isEmpty())
    }

    @Test
    fun longSpeechIsCutBetweenTheSoftAndHardLimits() {
        val u = Utterances()
        val closed = u.feed(mono(tone(30_000)))
        assertEquals(1, closed.size)
        assertTrue(closed[0].samples.size / 16L in Utterances.SOFT_MAX_MS..Utterances.HARD_MAX_MS)
    }

    private fun TestScope.transcriber(engine: FakeEngine) =
        LiveTranscriber("he", "m", backgroundScope, StandardTestDispatcher(testScheduler)) { engine }

    @Test
    fun eachLineBecomesASegment() = runTest {
        val engine = FakeEngine()
        val live = transcriber(engine)
        val pcm = silence(500) + tone(3_500) + silence(800) + tone(1_000) + silence(2_200)
        live.feed(pcm, pcm.size)
        runCurrent()
        val out = live.output.value
        assertEquals(listOf("line 1", "line 2"), out.segments.map { it.text })
        assertTrue(out.segments[0].endMillis <= out.segments[1].startMillis)
        assertEquals("m", out.segments[0].modelId)
        assertNull(out.partial)
        assertEquals(listOf<String?>(null, null), engine.prompts)
    }

    @Test
    fun theLineBeingSpokenGetsAPartial() = runTest {
        val engine = FakeEngine()
        val live = transcriber(engine)
        val pcm = silence(300) + tone(2_000)
        live.feed(pcm, pcm.size)
        runCurrent()
        assertEquals("line 1", live.output.value.partial)
        assertTrue(live.output.value.segments.isEmpty())
        // Not again until there is enough new audio.
        live.feed(tone(1_000), tone(1_000).size)
        runCurrent()
        assertEquals(1, engine.calls.size)
        live.feed(tone(1_200), tone(1_200).size)
        runCurrent()
        assertEquals("line 2", live.output.value.partial)
        // A long line gets no more partials until it ends.
        live.feed(tone(8_000), tone(8_000).size)
        runCurrent()
        val calls = engine.calls.size
        live.feed(tone(2_500), tone(2_500).size)
        runCurrent()
        assertEquals(calls, engine.calls.size)
    }

    @Test
    fun finishTranscribesWhatIsLeftAndClosesTheModel() = runTest {
        val engine = FakeEngine()
        val live = transcriber(engine)
        val pcm = silence(300) + tone(800)
        live.feed(pcm, pcm.size)
        live.finish()
        assertEquals(listOf("line 1"), live.output.value.segments.map { it.text })
        assertTrue(engine.closed)
    }

    @Test
    fun linesAreTidied() {
        assertEquals("מול התסקין ומול החירים.", cleanLine(" , מול התסקין ומול החירים."))
        assertEquals("משלח זה, אתם,", cleanLine(", משלח זה, אתם, אתם, אתם, אתם, אתם,"))
        assertEquals("שור לו, אבר, חותך.", cleanLine("שור לו, אבר, חותך."))
        assertEquals("go go", cleanLine("go go"))
        assertEquals("אבישה ראשונה, תודה רבה. אני לא שומע אותך. אבישה ראשונה.", cleanLine("אבישה ראשונה, תודה רבה. אני לא שומע אותך. אני לא שומע אותך. אני לא שומע אותך. אני לא שומע אותך.  אבישה ראשונה."))
        assertEquals("", cleanLine(" , . "))
    }

    @Test
    fun piecesThatAreProbablyNotSpeechAreDropped() = runTest {
        val engine = object : SpeechEngine {
            override fun transcribe(samples: FloatArray, language: String, prompt: String?) =
                listOf(Piece(0, 1000, "שלום", 0.1f), Piece(1000, 2000, "תודה רבה.", 0.9f))
            override fun close() {}
        }
        val live = LiveTranscriber("he", "m", backgroundScope, StandardTestDispatcher(testScheduler)) { engine }
        val pcm = silence(300) + tone(800) + silence(2_200)
        live.feed(pcm, pcm.size)
        runCurrent()
        assertEquals(listOf("שלום"), live.output.value.segments.map { it.text })
    }

    @Test
    fun aModelThatFailsToLoadIsReported() = runTest {
        val live = LiveTranscriber("he", "m", backgroundScope, StandardTestDispatcher(testScheduler)) { error("no file") }
        runCurrent()
        assertEquals("no file", live.output.value.error)
    }
}
