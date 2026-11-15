package com.chencohensolution.transcriptor.notebook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The labels and the moments on the phone (and in the library), as the first screen lists them. */
data class Library(
    /** The labels in the order they were made. */
    val labels: List<Label> = emptyList(),
    /** Every moment, newest first, with only labels that still exist. */
    val moments: List<LectureSummary> = emptyList(),
    /** The last moment's language; the phone's before any. */
    val lastLanguage: String = "en",
)

/** A moment recording now, with the labels it was started with, for the first screen's banner. */
data class RecordingNow(val labels: List<String>, val lectureTitle: String, val elapsedMillis: Long, val lectureId: String? = null)

/** What the first screen shows. */
data class NotebooksState(
    /** In the order they were made. */
    val labels: List<Label>,
    /** Every moment, newest first (the one recording now is the banner, not here). */
    val moments: List<LectureSummary>,
    val nowMillis: Long,
    /** The language the next moment records in: the guessed label's usual one, else the last moment's. */
    val language: String,
    /** The labels a moment started now would get, from when moments with them usually are; null when none fits. */
    val guess: LabelGuess?,
    /** For moments without labels: the label their time suggests, by moment id. */
    val suggested: Map<String, String>,
    val recording: RecordingNow? = null,
    /** Moments with a pass running on them (the speaker pass, refining, compressing to FLAC): their progress. */
    val working: Map<String, Float> = emptyMap(),
)

/**
 * The screen's state from the [library] at [now], with what is recording and running. The moment recording now is the
 * banner, not in the list.
 */
fun notebooksState(
    library: Library, now: Long, utcOffset: (Long) -> Long,
    recording: RecordingNow? = null, working: Map<String, Float> = emptyMap(),
): NotebooksState {
    val moments = library.moments.filter { it.id != recording?.lectureId }
    val guess = guessLabels(moments, now, utcOffset)
    val language = guess?.labels?.firstOrNull()?.let { label -> usualLanguage(moments.filter { label in it.labels }) } ?: library.lastLanguage
    return NotebooksState(
        labels = library.labels,
        moments = moments,
        nowMillis = now,
        language = language,
        guess = guess,
        suggested = moments.filter { it.labels.isEmpty() }
            .mapNotNull { m -> guessLabels(moments, m.startedAtMillis, utcOffset)?.labels?.firstOrNull()?.let { m.id to it } }.toMap(),
        recording = recording,
        working = working,
    )
}

/** The language most of [moments] are in; null for none. */
private fun usualLanguage(moments: List<LectureSummary>): String? =
    moments.groupingBy { it.language }.eachCount().maxByOrNull { it.value }?.key

/** A moment as a list shows it. */
fun Lecture.summary() = LectureSummary(id, title, startedAtMillis, durationMillis, notes.size, refinedWith != null, language, labels)

/** The names of [ids]' labels, in order, joined ("Psychology, Exam"); empty for none. */
fun labelNames(ids: List<String>, labels: List<Label>): String = ids.mapNotNull { id -> labels.firstOrNull { it.id == id }?.name }.joinToString(", ")

/**
 * The labels and the moments ([library]), read from the stores by [reload] (files: not on the main thread), and the
 * changes the first screen makes: labels made, renamed and deleted, moments labeled and deleted. A moment that is
 * [busy] (being recorded, or a pass runs on it) is left as it is, since its own save would undo the change.
 */
class NotebooksController(
    private val labels: LabelStore,
    private val lectures: LectureStore,
    private val now: () -> Long,
    /** The language before any moment: the phone's ([com.chencohensolution.transcriptor.models.Languages.default]). */
    private val defaultLanguage: () -> String = { "en" },
) {
    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    fun reload() {
        val all = lectures.all().sortedByDescending { it.startedAtMillis }
        val known = labels.all()
        val ids = known.map { it.id }.toSet()
        _library.value = Library(
            labels = known,
            moments = all.map { it.summary().let { s -> s.copy(labels = s.labels.filter { l -> l in ids }) } },
            lastLanguage = all.firstOrNull()?.language ?: defaultLanguage(),
        )
    }

    fun newLabel(name: String): Label = labels.create(name, now()).also { reload() }

    /** Adds [add] and removes [remove] on each of [lectureIds] that isn't [busy]; returns the ids changed. */
    fun setLabels(lectureIds: Collection<String>, add: Set<String>, remove: Set<String>, busy: (String) -> Boolean = { false }): List<String> {
        val changed = lectureIds.filter { id ->
            if (busy(id)) return@filter false
            val lecture = lectures.load(id) ?: return@filter false
            val next = (lecture.labels + add).distinct() - remove
            next != lecture.labels && lectures.setLabels(lecture, next) != lecture
        }
        reload()
        return changed
    }

    fun renameLabel(id: String, name: String) {
        labels.rename(id, name)
        reload()
    }

    /** Deletes a label; the moments that had it stay, without it ([busy] ones keep its id, shown as nothing). */
    fun deleteLabel(id: String, busy: (String) -> Boolean = { false }) {
        labels.delete(id)
        for (m in _library.value.moments.filter { id in it.labels }) {
            if (busy(m.id)) continue
            lectures.load(m.id)?.let { lectures.setLabels(it, it.labels - id) }
        }
        reload()
    }

    /**
     * Deletes moments for good (by id), skipping those [busy] says are being recorded or worked on; returns the ids
     * deleted.
     */
    fun delete(lectureIds: Collection<String>, busy: (String) -> Boolean = { false }): List<String> {
        val deleted = lectureIds.filter { id -> !busy(id) && lectures.delete(id) }
        reload()
        return deleted
    }
}
