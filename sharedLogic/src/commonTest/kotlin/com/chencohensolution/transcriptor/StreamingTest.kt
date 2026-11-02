package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.speech.StreamingEngine
import com.chencohensolution.transcriptor.speech.SpeakerLabeler
import com.chencohensolution.transcriptor.speech.SpeakerTracker
import com.chencohensolution.transcriptor.speech.StreamingTranscriber
import com.chencohensolution.transcriptor.speech.TimedToken
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
class FakeStreaming(private val withTimes: Boolean = false) : StreamingEngine {
    private var loud = 0
    private var quiet = 0
    private var words = 0
    private var position = 0 // samples since the last reset
    private var wordStart = -1
    private val times = mutableListOf<Float>()
    var resets = 0
    var closed = false
    override fun accept(samples: FloatArray) {
        for (s in samples) {
            if (abs(s) > 0.05f) {
                if (wordStart < 0) wordStart = position
                loud++; quiet = 0
            } else quiet++
            position++
            if (loud >= 12_800) { loud = 0; words++; times += wordStart / 16_000f; wordStart = -1 }
        }
    }
    override val text get() = (1..words).joinToString(" ") { "מילה$it" }
    // Each word, then a comma as a token of its own (as the real model writes punctuation).
    override val tokens get() = if (withTimes) (1..words).flatMap { listOf(TimedToken(" מילה$it", times[it - 1]), TimedToken(",", times[it - 1] + 0.5f)) } else emptyList()
    override fun isEndpoint() = words > 0 && quiet >= 12_800
    override fun reset() { loud = 0; quiet = 0; words = 0; position = 0; wordStart = -1; times.clear(); resets++ }
    override fun flush() { if (loud > 4_000) { words++; times += maxOf(wordStart, 0) / 16_000f }; loud = 0 }
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
    fun wordTimesGiveTheLinesTimes() = runTest {
        val live = StreamingTranscriber("z", backgroundScope, StandardTestDispatcher(testScheduler)) { FakeStreaming(withTimes = true) }
        feedLive(live, silence(1_000) + tone(2_000) + silence(1_000))
        val line = live.output.value.segments.single()
        // The first word starts at 1 s (less the lead); the last word starts about 1.2 s later (plus the tail).
        assertTrue(line.startMillis in 750L..1_000L, "start ${line.startMillis}")
        assertTrue(line.endMillis in 2_400L..3_200L, "end ${line.endMillis}")
    }

    @Test
    fun aLineWhereTheVoiceChangesIsSplitAtAWord() = runTest {
        val tracker = SpeakerTracker()
        val labeler = SpeakerLabeler(tracker) { PitchEmbedder() }
        val live = StreamingTranscriber("z", backgroundScope, StandardTestDispatcher(testScheduler), labeler) { FakeStreaming(withTimes = true) }
        // Two voices, one after the other with no pause: the model hears one line.
        feedLive(live, silence(500) + tone(4_000, hertz = 300) + tone(4_000, hertz = 800) + silence(1_000))
        val lines = live.output.value.segments
        assertEquals(2, lines.size, lines.toString())
        assertEquals(listOf("speaker-1", "speaker-2"), lines.map { it.speakerId })
        // The voice changes at 4.5 s, inside a word (each is about 0.9 s): the split is at a word next to it.
        assertTrue(lines[0].endMillis in 3_600L..5_400L, "split at ${lines[0].endMillis}")
        assertEquals(lines[0].endMillis, lines[1].startMillis)
        // Every word once, in order, with its punctuation.
        val words = (lines[0].text + " " + lines[1].text).split(" ")
        assertEquals((1..words.size).map { "מילה$it," }, words)
        // The lines keep their words with their times, for the speaker pass.
        assertEquals(words.size, lines.sumOf { it.words.size })
        assertTrue(lines[1].words.first().atMillis >= lines[1].startMillis)
    }

    @Test
    fun oneVoiceStaysOneLine() = runTest {
        val labeler = SpeakerLabeler(SpeakerTracker()) { PitchEmbedder() }
        val live = StreamingTranscriber("z", backgroundScope, StandardTestDispatcher(testScheduler), labeler) { FakeStreaming(withTimes = true) }
        feedLive(live, silence(500) + tone(8_000) + silence(1_000))
        assertEquals(listOf("speaker-1"), live.output.value.segments.map { it.speakerId })
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
