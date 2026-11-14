package com.chencohensolution.transcriptor.notebook

import kotlin.math.abs

/**
 * The labels moments usually have at this time ([labels], the most usual first): each on at least two earlier moments
 * on the same weekday, within an hour of it; those after the first are on most of its moments. [usualStartMillis] is
 * the latest moment of the first label (its weekday and time say "Monday at 10:00").
 */
data class LabelGuess(val labels: List<String>, val usualStartMillis: Long)

/**
 * The labels moments usually have at [atMillis], from [moments] on the same weekday starting within [windowMinutes] of
 * its time of day: the label on the most of them, at least [minTimes] (a habit, not a one-off; a tie goes to the one
 * used most recently), with those on more than half of its moments ("Psychology" and its lecturer; an "Exam" on two of
 * six Monday classes is not their label). Weekdays and times are the phone's: [utcOffset] gives its offset from UTC at
 * a time. Moments within 12 hours before [atMillis] don't count: they are this class, not its habit. Null when no label
 * is usual then.
 */
fun guessLabels(
    moments: List<LectureSummary>, atMillis: Long, utcOffset: (Long) -> Long,
    windowMinutes: Int = 60, minTimes: Int = 2,
): LabelGuess? {
    val (day, minute) = slotOf(atMillis, utcOffset)
    val matching = moments.filter { m ->
        val (d, mm) = slotOf(m.startedAtMillis, utcOffset)
        m.startedAtMillis < atMillis - 12 * 60 * MINUTE && d == day && abs(mm - minute) <= windowMinutes
    }
    val usual = matching.flatMap { m -> m.labels.map { it to m } }.groupBy({ it.first }, { it.second })
        .filterValues { it.size >= minTimes }
        .map { (label, ms) -> Triple(label, ms, ms.maxOf { it.startedAtMillis }) }
        .sortedWith(compareByDescending<Triple<String, List<LectureSummary>, Long>> { it.second.size }.thenByDescending { it.third })
    val top = usual.firstOrNull() ?: return null
    // With it, the labels on most of its moments ("Psychology" and the lecturer), not one now and then ("Exam").
    val along = usual.drop(1).filter { (label, _, _) -> top.second.count { label in it.labels } * 2 > top.second.size }
    return LabelGuess(listOf(top.first) + along.map { it.first }, top.third)
}
private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE

/** The weekday (0 is Thursday, as 1970-01-01 was) and the minute of the day, in the phone's time. */
private fun slotOf(millis: Long, utcOffset: (Long) -> Long): Pair<Int, Int> {
    val local = millis + utcOffset(millis)
    return local.floorDiv(DAY).mod(7L).toInt() to (local.mod(DAY) / MINUTE).toInt()
}
