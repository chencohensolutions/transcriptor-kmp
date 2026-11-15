package com.chencohensolution.transcriptor.review

import com.chencohensolution.transcriptor.notebook.Lecture

/**
 * A speaker of a moment as "Who was here?" lists them: how much they talked ([talkMillis], and its [share] of all the
 * talking, 0..1), in how many [lines], and a piece to hear who it is: their longest line, from [sampleStartMillis] to
 * [sampleEndMillis].
 */
data class Person(
    val speakerId: String,
    /** The name the user gave, or the app knew the voice by; empty for "Speaker N". */
    val name: String,
    val talkMillis: Long,
    val share: Float,
    val lines: Int,
    val sampleStartMillis: Long,
    val sampleEndMillis: Long,
) {
    /**
     * A voice with only a few short lines: often a piece of somebody else that live speaker turns split off, so the
     * screen offers to merge it.
     */
    val minor: Boolean get() = lines <= MINOR_LINES && share < MINOR_SHARE

    companion object {
        const val MINOR_LINES = 3
        const val MINOR_SHARE = 0.05f
    }
}

/** The moment's speakers who said something, the one who talked most first. */
fun peopleOf(lecture: Lecture): List<Person> {
    val bySpeaker = lecture.segments.filter { it.speakerId != null }.groupBy { it.speakerId!! }
    val all = bySpeaker.values.sumOf { lines -> lines.sumOf { it.endMillis - it.startMillis } }.coerceAtLeast(1)
    return lecture.speakers.mapNotNull { s ->
        val lines = bySpeaker[s.id] ?: return@mapNotNull null
        val talk = lines.sumOf { it.endMillis - it.startMillis }
        val sample = lines.maxBy { it.endMillis - it.startMillis }
        Person(s.id, s.name, talk, talk.toFloat() / all, lines.size, sample.startMillis, sample.endMillis)
    }.sortedByDescending { it.talkMillis }
}

/** [from]'s lines given to [into], and [from] gone: the two were one person. Nothing when either isn't a speaker. */
fun Lecture.mergingSpeakers(from: String, into: String): Lecture {
    if (from == into || speakers.none { it.id == from } || speakers.none { it.id == into }) return this
    return copy(
        segments = segments.map { if (it.speakerId == from) it.copy(speakerId = into) else it },
        speakers = speakers.filter { it.id != from },
    )
}
