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

    /** The recording was paused here. */
    data class Paused(val pause: Pause) : TimelineEntry {
        override val atMillis get() = pause.atMillis
    }
}

/**
 * Lines, notes and pauses in order; a note comes right after the line that was being said when its typing started, a
 * pause after the line it cut into (the next line starts after it).
 */
fun timelineOf(lecture: Lecture): List<TimelineEntry> {
    fun lineStartAt(at: Long) = lecture.segments.lastOrNull { it.startMillis <= at }?.startMillis ?: 0L
    // A pause right at a line's start belongs before that line.
    fun pauseAt(at: Long) = lecture.segments.lastOrNull { it.startMillis < at }?.startMillis ?: -1L
    return (lecture.segments.map { TimelineEntry.Speech(it) } + lecture.notes.map { TimelineEntry.Written(it) } + lecture.pauses.map { TimelineEntry.Paused(it) })
        .sortedWith(compareBy({ if (it is TimelineEntry.Paused) pauseAt(it.atMillis) else lineStartAt(it.atMillis) }, { it !is TimelineEntry.Speech }, { it.atMillis }))
}
