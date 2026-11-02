package com.chencohensolution.transcriptor.notebook

/** A line of speech or a note, in the order they happened. */
sealed interface TimelineEntry {
    val atMillis: Long

    data class Speech(val segment: Segment) : TimelineEntry {
        override val atMillis get() = segment.startMillis
    }

    data class Written(val note: Note) : TimelineEntry {
        override val atMillis get() = note.atMillis
    }
}

/** Lines and notes in order; a note comes right after the line that was being said when its typing started. */
fun timelineOf(lecture: Lecture): List<TimelineEntry> {
    fun lineStartAt(at: Long) = lecture.segments.lastOrNull { it.startMillis <= at }?.startMillis ?: 0L
    return (lecture.segments.map { TimelineEntry.Speech(it) } + lecture.notes.map { TimelineEntry.Written(it) })
        .sortedWith(compareBy({ lineStartAt(it.atMillis) }, { it is TimelineEntry.Written }, { it.atMillis }))
}
