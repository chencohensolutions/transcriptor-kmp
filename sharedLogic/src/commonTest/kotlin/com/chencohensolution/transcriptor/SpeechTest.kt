package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.speech.Downsampler
import com.chencohensolution.transcriptor.speech.LiveTranscriber
import com.chencohensolution.transcriptor.speech.Piece
import com.chencohensolution.transcriptor.speech.SpeechEngine
import com.chencohensolution.transcriptor.speech.Utterances
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
        val closed = u.feed(mono(silence(1_000) + tone(2_000) + silence(1_000)))
        assertEquals(1, closed.size)
        // Starts a little before the speech (pre-roll), ends after the pause that closed it.
        assertTrue(closed[0].startSample / 16 in 700L..1_000L, "start ${closed[0].startSample / 16}")
        assertTrue(closed[0].endSample / 16 in 2_550L..3_700L, "end ${closed[0].endSample / 16}")
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
        val pcm = silence(500) + tone(2_000) + silence(800) + tone(1_000) + silence(800)
        live.feed(pcm, pcm.size)
        runCurrent()
        val out = live.output.value
        assertEquals(listOf("line 1", "line 2"), out.segments.map { it.text })
        assertTrue(out.segments[0].endMillis <= out.segments[1].startMillis)
        assertEquals("m", out.segments[0].modelId)
        assertNull(out.partial)
        assertEquals(listOf(null, "line 1"), engine.prompts)
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
        live.feed(tone(500), tone(500).size)
        runCurrent()
        assertEquals(1, engine.calls.size)
        live.feed(tone(1_200), tone(1_200).size)
        runCurrent()
        assertEquals("line 2", live.output.value.partial)
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
    fun aModelThatFailsToLoadIsReported() = runTest {
        val live = LiveTranscriber("he", "m", backgroundScope, StandardTestDispatcher(testScheduler)) { error("no file") }
        runCurrent()
        assertEquals("no file", live.output.value.error)
    }
}
