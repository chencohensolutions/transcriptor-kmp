package com.chencohensolution.transcriptor.notebook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Once per place (the app's own storage, the library): notebooks become labels. Each notebook in `notebooks.json`
 * becomes a label with its id, name and color (`labels.json`); every lecture folder `notebooks/<notebook>/<lecture>/`
 * moves to `moments/<lecture>/` and gets its notebook as its label (those filed in no notebook, `unfiled`, or in the
 * `default` one of before notebooks, get none); the notebooks' voices join the app's one list (`voices.json`), a name two
 * notebooks knew kept apart as "<name> · <notebook>". Nothing is deleted but the emptied folders and, last, the old
 * file; a run cut off (or a lecture that couldn't move) goes on at the next start. [utcOffset] dates the transcripts it
 * rewrites. The labels and voices go to [into]: the library, when the app's own storage is moved while there is one
 * (where the app keeps them). Files: not on the main thread.
 */
fun migrateToLabels(place: Place, now: Long, utcOffset: (Long) -> Long = { 0 }, into: Place = place) {
    val fs = place.fileSystem
    val file = place.root / NOTEBOOKS_FILE
    val hasFile = place.exists(file)
    if (!hasFile && !place.exists(place.notebooks)) return
    val notebooks = if (hasFile) json.decodeFromString<NotebooksFileV1>(fs.read(file) { readUtf8() }).notebooks else emptyList()
    LabelStore { into }.add(notebooks.map { Label(it.id, it.title, it.color) }, now)

    val folders = place.folders(place.notebooks)
    val voicesOf = folders.associate { it.name to VoiceStore({ "" }) { Place(fs, it) }.all() }
    val clashing = voicesOf.values.flatMap { vs -> vs.map { it.name }.distinct() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val titles = notebooks.associate { it.id to it.title }
    VoiceStore({ "" }) { into }.add(voicesOf.flatMap { (notebook, vs) ->
        vs.map { v -> if (v.name in clashing) v.copy(name = "${v.name} · ${titles[notebook] ?: notebook}") else v }
    })

    val lectures = LectureStore(fs, place.root, utcOffset)
    for (notebook in folders) {
        // By the folder's name: lectures still in the app's storage after the notebooks' file moved to the library
        // keep their notebook too (its label comes with the library's file).
        val label = notebook.name.takeIf { it != "unfiled" && it != "default" }
        for (lecture in place.folders(notebook)) {
            val to = place.folder(lecture.name)
            if (place.exists(to)) continue
            fs.createDirectories(place.moments)
            fs.atomicMove(lecture, to)
            val moved = runCatching { lectures.load(lecture.name) }.getOrNull() ?: continue
            if (label != null && label !in moved.labels) lectures.setLabels(moved, moved.labels + label)
        }
        runCatching { fs.delete(notebook / VoiceStore.FILE) }
        if (fs.listOrNull(notebook).isNullOrEmpty()) runCatching { fs.delete(notebook) }
    }
    if (fs.listOrNull(place.notebooks).isNullOrEmpty()) {
        runCatching { fs.delete(place.notebooks) }
        if (hasFile) fs.delete(file)
    }
}

/** Before labels: the notebooks' file, in each place. */
const val NOTEBOOKS_FILE = "notebooks.json"

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class NotebooksFileV1(val v: Int = 1, val notebooks: List<NotebookV1> = emptyList())

@Serializable
private data class NotebookV1(val id: String, val title: String, val language: String = "en", val color: Int = 0)
