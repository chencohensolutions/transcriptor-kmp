package com.chencohensolution.transcriptor.notebook

/**
 * Makes [library] the lectures folder: the notebooks (merged with any it already has, as after a reinstall) and their
 * voices move there from where they were ([lectures]' library before, or the app's own storage), then the lectures
 * follow, checked file by file. From the app's storage, the [busy] ones (being recorded, worked on or open) stay and
 * follow on a later [archiveFinished]; from an old library every one moves, since the old one is let go. Lectures
 * already in [library] stay as they are. The library changes once all moved: a failure leaves the old place in use.
 * Files: not on the main thread.
 */
fun adoptLibrary(lectures: LectureStore, notebooks: NotebookStore, library: Place, busy: Set<String> = emptySet(), progress: (Float) -> Unit = {}) {
    val from = lectures.library ?: lectures.local
    if (from == library) return
    notebooks.merge(from, library)
    for (notebook in from.folders(from.notebooks)) {
        val voices = notebook / "voices.json"
        val there = library.notebooks / notebook.name / "voices.json"
        if (from.exists(voices) && !library.exists(there)) copyChecked(from, voices, library, there)
    }
    val moving = from.folders(from.notebooks).flatMap { n -> from.folders(n).map { n.name to it.name } }
    val fromHere = from == lectures.local
    moving.forEachIndexed { i, (notebookId, lectureId) ->
        val folder = from.folder(notebookId, lectureId)
        if ((!fromHere || lectureId !in busy) && !library.exists(library.folder(notebookId, lectureId))) {
            copyChecked(from, folder, library, library.folder(notebookId, lectureId))
            from.fileSystem.deleteRecursively(folder)
        }
        progress((i + 1f) / moving.size)
    }
    lectures.library = library
    for (notebook in from.folders(from.notebooks)) {
        from.fileSystem.delete(notebook / "voices.json")
        if (from.fileSystem.list(notebook).isEmpty()) from.fileSystem.delete(notebook)
    }
}

/**
 * Moves the finished lectures still in the app's own storage to the library, except the busy ones (being recorded,
 * worked on or open: [busy], asked just before each one moves) and those waiting to be compressed to FLAC. A lecture
 * that fails to copy stays and is tried again next time. Returns how many moved.
 */
fun archiveFinished(lectures: LectureStore, busy: (lectureId: String) -> Boolean): Int {
    if (lectures.library == null) return 0
    return lectures.localLectures().count { lecture ->
        !lecture.pendingFlac && !busy(lecture.id) && runCatching { lectures.archive(lecture) }.getOrDefault(false)
    }
}

fun archiveFinished(lectures: LectureStore, busy: Set<String>): Int = archiveFinished(lectures) { it in busy }
