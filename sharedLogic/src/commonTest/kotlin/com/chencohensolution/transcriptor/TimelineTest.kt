package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.TimelineEntry
import com.chencohensolution.transcriptor.notebook.timelineOf
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineTest {
    private fun lecture(segments: List<Segment>, notes: List<Note>) =
        Lecture("l", "nb", "t", "he", 0, 60_000, segments, notes)

    private fun line(id: String, start: Long, end: Long) = Segment(id, start, end, id, "m")

    private fun ids(lecture: Lecture) = timelineOf(lecture).map {
        when (it) {
            is TimelineEntry.Speech -> it.segment.id
            is TimelineEntry.Written -> it.note.id
        }
    }

    @Test
    fun aNoteComesRightAfterTheLineBeingSaidWhenItsTypingStarted() {
        val lecture = lecture(
            listOf(line("s1", 0, 10_000), line("s2", 10_000, 20_000), line("s3", 20_000, 30_000)),
            listOf(Note("n1", 15_000, "x")),
        )
        assertEquals(listOf("s1", "s2", "n1", "s3"), ids(lecture))
    }

    @Test
    fun aNoteBeforeAnyLineComesFirst() {
        val lecture = lecture(listOf(line("s1", 5_000, 10_000)), listOf(Note("n1", 1_000, "x")))
        assertEquals(listOf("n1", "s1"), ids(lecture))
    }

    @Test
    fun notesWithoutLinesAreInTimeOrder() {
        val lecture = lecture(emptyList(), listOf(Note("n2", 9_000, "b"), Note("n1", 3_000, "a")))
        assertEquals(listOf("n1", "n2"), ids(lecture))
    }
}
