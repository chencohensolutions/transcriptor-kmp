package com.chencohensolution.transcriptor.notebook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The notebooks and the lectures on the phone, as the Notebooks screen lists them. */
data class Library(
    /** The notebooks in the order they were made, each with its lectures, newest first. */
    val notebooks: List<Notebook> = emptyList(),
    /** Lectures recorded without a notebook, newest first. */
    val unfiled: List<LectureSummary> = emptyList(),
    /** The language a lecture with no notebook records in: the last lecture's, Hebrew before any. */
    val unfiledLanguage: String = "he",
)

/** A lecture recording now, in a notebook or in none yet ([notebookId] null), for the Notebooks screen's banner. */
data class RecordingNow(val notebookId: String?, val lectureTitle: String, val elapsedMillis: Long)

/** What the Notebooks screen shows. */
data class NotebooksState(
    val notebooks: List<Notebook>,
    val unfiled: List<LectureSummary>,
    val nowMillis: Long,
    val unfiledLanguage: String,
    /** The notebook a lecture started now would be in, from when its lectures usually are; null when none fits. */
    val guess: NotebookGuess?,
    /** For unfiled lectures: the notebook their time suggests, by lecture id. */
    val suggested: Map<String, NotebookGuess>,
    val recording: RecordingNow? = null,
    /** Lectures with a pass running on them (the speaker pass, refining, compressing to FLAC): their progress. */
    val working: Map<String, Float> = emptyMap(),
)

/** The screen's state from the [library] at [now], with what is recording and running. */
fun notebooksState(
    library: Library, now: Long, utcOffset: (Long) -> Long,
    recording: RecordingNow? = null, working: Map<String, Float> = emptyMap(),
) = NotebooksState(
    notebooks = library.notebooks,
    unfiled = library.unfiled,
    nowMillis = now,
    unfiledLanguage = library.unfiledLanguage,
    guess = guessNotebook(library.notebooks, now, utcOffset),
    suggested = library.unfiled.mapNotNull { l -> guessNotebook(library.notebooks, l.startedAtMillis, utcOffset)?.let { l.id to it } }.toMap(),
    recording = recording,
    working = working,
)

/** A lecture as a list shows it. */
fun Lecture.summary() = LectureSummary(id, title, startedAtMillis, durationMillis, notes.size, refinedWith != null)

/**
 * The notebooks and their lectures ([library]), read from the stores by [reload] (files: not on the main thread), and
 * the changes the Notebooks screen makes: new notebooks, unfiled lectures filed.
 */
class NotebooksController(private val notebooks: NotebookStore, private val lectures: LectureStore, private val now: () -> Long) {
    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    fun reload() {
        val all = lectures.all().sortedByDescending { it.startedAtMillis }
        val byNotebook = all.groupBy { it.notebookId }
        _library.value = Library(
            notebooks = notebooks.all().map { n -> n.copy(lectures = byNotebook[n.id].orEmpty().map { it.summary() }) },
            unfiled = byNotebook[UNFILED].orEmpty().map { it.summary() },
            unfiledLanguage = all.firstOrNull()?.language ?: "he",
        )
    }

    fun create(title: String, language: String): Notebook = notebooks.create(title, language, now()).also { reload() }

    /** Files an unfiled lecture into a notebook; returns it there, or null when it is no longer unfiled. */
    fun assign(lectureId: String, notebookId: String): Lecture? {
        val lecture = lectures.load(UNFILED, lectureId) ?: return null
        return lectures.move(lecture, notebookId).also { reload() }
    }

    /** Makes a notebook and files an unfiled lecture into it. */
    fun assignToNew(lectureId: String, title: String, language: String): Lecture? = assign(lectureId, create(title, language).id)
}
