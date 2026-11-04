package com.chencohensolution.transcriptor.notebook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** The notebook id of lectures recorded without a notebook, waiting to be filed: never in `notebooks.json`. */
const val UNFILED = "unfiled"

/**
 * The notebooks: `notebooks.json` (version 1) in the [place] the lectures are kept (the library once there is one),
 * written to a temp file and then renamed. Their lectures are in [LectureStore]'s `notebooks/<id>/` folders; the
 * notebooks here come without them.
 */
class NotebookStore(private val place: () -> Place) {
    constructor(fileSystem: FileSystem, root: Path) : this({ Place(fileSystem, root) })

    private val fileSystem: FileSystem get() = place().fileSystem
    private val root: Path get() = place().root
    private val file get() = root / "notebooks.json"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    /** The notebooks, in the order they were made. */
    fun all(): List<Notebook> = read().notebooks.map { Notebook(it.id, it.title, it.language, emptyList(), it.color) }

    /** A new notebook, with the first color no other notebook has. */
    fun create(title: String, language: String, now: Long): Notebook {
        val file = read()
        val used = file.notebooks.map { it.color }.toSet()
        val color = generateSequence(0) { it + 1 }.first { it !in used }
        var id = "nb-$now"
        while (file.notebooks.any { it.id == id }) id += "x"
        val notebook = NotebookV1(id, title.trim(), language, color, now)
        write(file.copy(notebooks = file.notebooks + notebook))
        return Notebook(id, notebook.title, language, emptyList(), color)
    }

    /**
     * Once, before the first `notebooks.json`: the lectures of the notebook the app used before notebooks existed
     * (`default`) become unfiled, and its voices are dropped, since no notebook owns them now.
     */
    fun migrate(lectures: LectureStore) {
        if (fileSystem.exists(file)) return
        val old = root / "notebooks" / "default"
        if (fileSystem.exists(old)) {
            for (folder in fileSystem.list(old).filter { fileSystem.metadataOrNull(it)?.isDirectory == true }) {
                val lecture = lectures.load("default", folder.name) ?: continue
                lectures.move(lecture, UNFILED)
            }
            fileSystem.deleteRecursively(old)
        }
        write(NotebooksFileV1())
    }

    /**
     * Adds to [to]'s notebooks those of [from] it lacks (by id; [to]'s win, as after a reinstall), then deletes [from]'s
     * file: when the notebooks move to the library.
     */
    fun merge(from: Place, to: Place) {
        val there = read(to)
        val here = read(from).notebooks.filter { n -> there.notebooks.none { it.id == n.id } }
        write(there.copy(notebooks = there.notebooks + here), to)
        from.fileSystem.delete(from.root / "notebooks.json")
    }

    private fun read(at: Place = place()): NotebooksFileV1 {
        val file = at.root / "notebooks.json"
        return if (at.exists(file)) json.decodeFromString(at.fileSystem.read(file) { readUtf8() }) else NotebooksFileV1()
    }

    private fun write(notebooks: NotebooksFileV1, at: Place = place()) {
        at.fileSystem.createDirectories(at.root)
        val temp = at.root / "notebooks.json.tmp"
        at.fileSystem.write(temp) { writeUtf8(json.encodeToString(notebooks)) }
        at.fileSystem.atomicMove(temp, at.root / "notebooks.json")
    }
}

@Serializable
private data class NotebooksFileV1(val v: Int = 1, val notebooks: List<NotebookV1> = emptyList())

@Serializable
private data class NotebookV1(val id: String, val title: String, val language: String, val color: Int, val createdAt: Long)
