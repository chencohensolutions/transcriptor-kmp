package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Notebook
import java.util.Calendar
import java.util.TimeZone

/**
 * Notebooks, lectures first, with lectures not in a notebook yet and a guess of the notebook a lecture now belongs to.
 * [notebooks] is the screen's usual state; a recording with no notebook has a null [RecordingNow.notebookId].
 */
data class NotebooksStateC2(
    val notebooks: NotebooksState,
    /** Lectures recorded without a notebook, newest first, waiting to be assigned. */
    val unfiled: List<LectureSummary>,
    /** The notebook a lecture started now would be in, from when its lectures usually are; null when none fits. */
    val guess: NotebookGuess?,
    /** For unfiled lectures: the notebook their time suggests, by lecture id. */
    val suggested: Map<String, NotebookGuess>,
)

/**
 * A notebook whose lectures are usually at this time: [times] earlier lectures on the same weekday, within an hour of
 * it; [usualStartMillis] is the latest of them (its weekday and time say "Mondays at 10:00").
 */
data class NotebookGuess(val notebookId: String, val times: Int, val usualStartMillis: Long)

class NotebooksActionsC2(
    val notebooks: NotebooksActions,
    /** Starts a lecture in a notebook, or in none (null) to assign after. */
    val record: (notebookId: String?) -> Unit = {},
    /** Moves an unfiled lecture into a notebook. */
    val assign: (lectureId: String, notebookId: String) -> Unit = { _, _ -> },
    /** Makes a notebook and moves an unfiled lecture into it. */
    val assignToNew: (lectureId: String, title: String, language: String) -> Unit = { _, _, _ -> },
)

/**
 * The notebook whose lectures are usually at [atMillis]: the one with the most lectures on the same weekday, starting
 * within [windowMinutes] of its time of day, at least [minTimes] of them (a habit, not a one-off). Weekdays and times
 * are in [zone], the phone's. Lectures within 12 hours before [atMillis] don't count: they are this class, not its
 * habit. To move to the shared logic (with kotlinx-datetime instead of Calendar).
 */
fun guessNotebook(
    notebooks: List<Notebook>, atMillis: Long, zone: TimeZone = TimeZone.getDefault(),
    windowMinutes: Int = 60, minTimes: Int = 2,
): NotebookGuess? {
    fun slot(millis: Long) = Calendar.getInstance(zone).apply { timeInMillis = millis }.let {
        it.get(Calendar.DAY_OF_WEEK) to it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }
    val (day, minute) = slot(atMillis)
    return notebooks.mapNotNull { notebook ->
        val matching = notebook.lectures.filter { lecture ->
            val (d, m) = slot(lecture.startedAtMillis)
            lecture.startedAtMillis < atMillis - 12 * 60 * 60_000L && d == day && kotlin.math.abs(m - minute) <= windowMinutes
        }
        if (matching.size < minTimes) null
        else NotebookGuess(notebook.id, matching.size, matching.maxOf { it.startedAtMillis })
    }.maxWithOrNull(compareBy<NotebookGuess> { it.times }.thenBy { it.usualStartMillis })
}
