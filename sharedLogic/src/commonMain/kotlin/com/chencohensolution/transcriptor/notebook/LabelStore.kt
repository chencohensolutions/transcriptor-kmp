package com.chencohensolution.transcriptor.notebook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * The labels: `labels.json` (version 1) in the [place] the moments are kept (the library once there is one), written to
 * a temp file and then renamed. Which moments have a label is in each moment's `lecture.json`.
 */
class LabelStore(private val place: () -> Place) {
    constructor(fileSystem: FileSystem, root: Path) : this({ Place(fileSystem, root) })

    /** The labels, in the order they were made. */
    fun all(): List<Label> = read().labels.map { it.toLabel() }

    /** A new label, with the first color no other label has; an existing one when the name is taken (any case). */
    fun create(name: String, now: Long): Label {
        val file = read()
        val clean = name.trim()
        file.labels.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it.toLabel() }
        val used = file.labels.map { it.color }.toSet()
        val color = generateSequence(0) { it + 1 }.first { it !in used }
        var id = "label-$now"
        while (file.labels.any { it.id == id }) id += "x"
        val label = LabelV1(id, clean, color, now)
        write(file.copy(labels = file.labels + label))
        return label.toLabel()
    }

    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val file = read()
        write(file.copy(labels = file.labels.map { if (it.id == id) it.copy(name = clean) else it }))
    }

    /** Takes the label out of the list; the moments that had it lose it ([NotebooksController.deleteLabel]). */
    fun delete(id: String) {
        val file = read()
        write(file.copy(labels = file.labels.filter { it.id != id }))
    }

    /** Adds labels kept elsewhere (the notebooks, as labels) that are not here yet, by id. */
    internal fun add(labels: List<Label>, now: Long) {
        val file = read()
        val added = labels.filter { l -> file.labels.none { it.id == l.id } }.map { LabelV1(it.id, it.name, it.color, now) }
        if (added.isNotEmpty()) write(file.copy(labels = file.labels + added))
    }

    /**
     * Adds to [to]'s labels those of [from] it lacks (by id; [to]'s win, as after a reinstall), then deletes [from]'s
     * file: when the labels move to the library.
     */
    fun merge(from: Place, to: Place) {
        val there = read(to)
        val here = read(from).labels.filter { l -> there.labels.none { it.id == l.id } }
        write(there.copy(labels = there.labels + here), to)
        if (from.exists(from.root / FILE)) from.fileSystem.delete(from.root / FILE)
    }

    private fun read(at: Place = place()): LabelsFileV1 {
        val file = at.root / FILE
        return if (at.exists(file)) json.decodeFromString(at.fileSystem.read(file) { readUtf8() }) else LabelsFileV1()
    }

    private fun write(labels: LabelsFileV1, at: Place = place()) {
        at.fileSystem.createDirectories(at.root)
        val temp = at.root / "$FILE.tmp"
        at.fileSystem.write(temp) { writeUtf8(json.encodeToString(labels)) }
        at.fileSystem.atomicMove(temp, at.root / FILE)
    }

    companion object {
        const val FILE = "labels.json"
    }
}

private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
private data class LabelsFileV1(val v: Int = 1, val labels: List<LabelV1> = emptyList())

@Serializable
private data class LabelV1(val id: String, val name: String, val color: Int, val createdAt: Long) {
    fun toLabel() = Label(id, name, color)
}
