package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.review.compareTranscripts
import kotlin.test.Test
import kotlin.test.assertEquals

class CompareTest {
    private fun line(id: String, start: Long, end: Long, text: String) = Segment(id, start, end, text, "m")

    @Test
    fun marksTheWordsThatDifferIgnoringPunctuation() {
        val now = listOf(line("s1", 0, 2000, "שלום, מה שלומך היום?"))
        val other = listOf(line("s1", 0, 2000, "שלום מה שלומכם היום"))
        val c = compareTranscripts(now, other).single()
        assertEquals(listOf(false, false, true, false), c.now.map { it.changed })
        assertEquals(listOf(false, false, true, false), c.other.map { it.changed })
        assertEquals(true, c.differs)
    }

    @Test
    fun linesCutElsewhereAreMatchedByTime() {
        val now = listOf(line("a", 0, 4000, "one two three four"), line("b", 4000, 8000, "five six"))
        val other = listOf(line("x", 0, 2000, "one two"), line("y", 2000, 4100, "three four"), line("z", 4100, 8000, "five six"))
        val c = compareTranscripts(now, other)
        assertEquals(2, c.size)
        assertEquals(false, c[0].differs)
        assertEquals(false, c[1].differs)
    }

    @Test
    fun aLineOnlyTheOtherHasComesOnItsOwn() {
        val now = listOf(line("a", 0, 1000, "hello"))
        val other = listOf(line("x", 0, 1000, "hello"), line("y", 5000, 6000, "extra words"))
        val c = compareTranscripts(now, other)
        assertEquals(2, c.size)
        assertEquals(true, c[1].differs)
        assertEquals(emptyList(), c[1].now)
    }
}
