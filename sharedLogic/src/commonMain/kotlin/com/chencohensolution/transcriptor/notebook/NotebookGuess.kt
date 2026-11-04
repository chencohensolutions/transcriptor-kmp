package com.chencohensolution.transcriptor.notebook

import kotlin.math.abs

/**
 * A notebook whose lectures are usually at this time: [times] earlier lectures on the same weekday, within an hour of
 * it; [usualStartMillis] is the latest of them (its weekday and time say "Monday at 10:00").
 */
data class NotebookGuess(val notebookId: String, val times: Int, val usualStartMillis: Long)

/**
 * The notebook whose lectures are usually at [atMillis]: the one with the most lectures on the same weekday, starting
 * within [windowMinutes] of its time of day, at least [minTimes] of them (a habit, not a one-off); a tie goes to the
 * one used most recently. Weekdays and times are the phone's: [utcOffset] gives its offset from UTC at a time. Lectures
 * within 12 hours before [atMillis] don't count: they are this class, not its habit.
 */
fun guessNotebook(
    notebooks: List<Notebook>, atMillis: Long, utcOffset: (Long) -> Long,
    windowMinutes: Int = 60, minTimes: Int = 2,
): NotebookGuess? {
    val (day, minute) = slotOf(atMillis, utcOffset)
    return notebooks.mapNotNull { notebook ->
        val matching = notebook.lectures.filter { lecture ->
            val (d, m) = slotOf(lecture.startedAtMillis, utcOffset)
            lecture.startedAtMillis < atMillis - 12 * 60 * MINUTE && d == day && abs(m - minute) <= windowMinutes
        }
        if (matching.size < minTimes) null
        else NotebookGuess(notebook.id, matching.size, matching.maxOf { it.startedAtMillis })
    }.maxWithOrNull(compareBy<NotebookGuess> { it.times }.thenBy { it.usualStartMillis })
}

private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE

/** The weekday (0 is Thursday, as 1970-01-01 was) and the minute of the day, in the phone's time. */
private fun slotOf(millis: Long, utcOffset: (Long) -> Long): Pair<Int, Int> {
    val local = millis + utcOffset(millis)
    return local.floorDiv(DAY).mod(7L).toInt() to (local.mod(DAY) / MINUTE).toInt()
}
