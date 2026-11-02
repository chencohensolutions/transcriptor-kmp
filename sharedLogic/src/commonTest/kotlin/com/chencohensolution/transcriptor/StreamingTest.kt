package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.speech.StreamingEngine
import com.chencohensolution.transcriptor.speech.StreamingTranscriber
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A word for every 12,800 loud samples (about a second of the test tone); an endpoint after 0.8 s of quiet after words. */
private class FakeStreaming : StreamingEngine {
    private var loud = 0
    private var quiet = 0
    private var words = 0
    var resets = 0
    var closed = false
    override fun accept(samples: FloatArray) {
        for (s in samples) {
            if (abs(s) > 0.05f) { loud++; quiet = 0 } else quiet++
            if (loud >= 12_800) { loud = 0; words++ }
        }
    }
    override val text get() = (1..words).joinToString(" ") { "מילה$it" }
    override fun isEndpoint() = words > 0 && quiet >= 12_800
    override fun reset() { loud = 0; quiet = 0; words = 0; resets++ }
    override fun flush() { if (loud > 4_000) words++ ; loud = 0 }
    override fun close() { closed = true }
}

/** As the recorder does: 20 ms at a time, each given to the worker before the next. */
private fun TestScope.feedLive(live: StreamingTranscriber, pcm: ByteArray) {
    for (i in pcm.indices step 3_840) {
        val piece = pcm.copyOfRange(i, minOf(pcm.size, i + 3_840))
        live.feed(piece, piece.size)
        runCurrent()
    }
}

class StreamingTest {
    private fun TestScope.transcriber(engine: FakeStreaming) =
        StreamingTranscriber("z", backgroundScope, StandardTestDispatcher(testScheduler)) { engine }

    @Test
    fun wordsAppearAsTheyAreSaidAndAPauseEndsTheLine() = runTest {
        val engine = FakeStreaming()
        val live = transcriber(engine)
        feedLive(live, silence(1_000) + tone(2_000))
        assertEquals("מילה1 מילה2", live.output.value.partial)
        assertTrue(live.output.value.segments.isEmpty())
        feedLive(live, silence(1_000))
        val line = live.output.value.segments.single()
        assertEquals("מילה1 מילה2", line.text)
        assertEquals("z", line.modelId)
        // When the first word appeared (about a second into the speech) less the model's chunk; the last word's end.
        assertTrue(line.startMillis in 600L..1_000L, "start ${line.startMillis}")
        assertTrue(line.endMillis in 2_700L..3_100L, "end ${line.endMillis}")
        assertNull(live.output.value.partial)
        assertEquals(1, engine.resets)
    }

    @Test
    fun pauseEndsTheLineBeingSpoken() = runTest {
        val engine = FakeStreaming()
        val live = transcriber(engine)
        live.feed(tone(1_500), tone(1_500).size)
        runCurrent()
        live.pause()
        runCurrent()
        assertEquals(listOf("מילה1"), live.output.value.segments.map { it.text })
    }

    @Test
    fun finishDecodesTheRestAndClosesTheModel() = runTest {
        val engine = FakeStreaming()
        val live = transcriber(engine)
        live.feed(tone(1_500), tone(1_500).size)
        live.finish()
        assertEquals(listOf("מילה1 מילה2"), live.output.value.segments.map { it.text })
        assertTrue(engine.closed)
    }

    @Test
    fun silenceMakesNoLines() = runTest {
        val engine = FakeStreaming()
        val live = transcriber(engine)
        live.feed(silence(5_000), silence(5_000).size)
        live.finish()
        assertTrue(live.output.value.segments.isEmpty())
    }
}
