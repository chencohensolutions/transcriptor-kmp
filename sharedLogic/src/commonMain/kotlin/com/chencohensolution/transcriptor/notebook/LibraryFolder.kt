package com.chencohensolution.transcriptor.notebook

/**
 * Makes [library] the lectures folder: the labels and voices (merged with any it already has, as after a reinstall)
 * move there from where they were ([lectures]' library before, or the app's own storage), then the moments follow,
 * checked file by file. A library from before labels is moved to labels first ([migrateToLabels]). From the app's
 * storage, the [busy] ones (being recorded, worked on or open) stay and follow on a later [archiveFinished]; from an
 * old library every one moves, since the old one is let go. Moments already in [library] stay as they are. The
 * library changes once all moved: a failure leaves the old place in use. Files: not on the main thread.
 */
fun adoptLibrary(
    lectures: LectureStore, labels: LabelStore, library: Place, busy: Set<String> = emptySet(),
    now: Long = 0, utcOffset: (Long) -> Long = { 0 }, progress: (Float) -> Unit = {},
) {
    val from = lectures.library ?: lectures.local
    if (from == library) return
    migrateToLabels(library, now, utcOffset)
    labels.merge(from, library)
    VoiceStore({ "" }) { library }.add(VoiceStore({ "" }) { from }.all())
    val moving = from.folders(from.moments).map { it.name }
    val fromHere = from == lectures.local
    moving.forEachIndexed { i, lectureId ->
        val folder = from.folder(lectureId)
        if ((!fromHere || lectureId !in busy) && !library.exists(library.folder(lectureId))) {
            copyChecked(from, folder, library, library.folder(lectureId))
            from.fileSystem.deleteRecursively(folder)
        }
        progress((i + 1f) / moving.size)
    }
    lectures.library = library
    runCatching { from.fileSystem.delete(from.root / VoiceStore.FILE) }
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
