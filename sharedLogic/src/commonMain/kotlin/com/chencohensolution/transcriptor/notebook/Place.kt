package com.chencohensolution.transcriptor.notebook

import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * Where the app's files are: the app's own storage, or the lectures folder the user picked outside it (the library).
 * Each holds `notebooks.json` and `notebooks/<notebook id>/` (`voices.json` and a folder per lecture) under [root].
 */
data class Place(val fileSystem: FileSystem, val root: Path) {
    val notebooks: Path get() = root / "notebooks"

    fun folder(notebookId: String, lectureId: String): Path = notebooks / notebookId / lectureId

    /** The folders in [dir]; none when it is missing or can't be read (a library whose access was revoked). */
    fun folders(dir: Path): List<Path> =
        runCatching { fileSystem.listOrNull(dir).orEmpty().filter { fileSystem.metadataOrNull(it)?.isDirectory == true } }.getOrDefault(emptyList())

    fun exists(path: Path) = runCatching { fileSystem.exists(path) }.getOrDefault(false)
}

/**
 * Copies [from] (a file or a folder, with everything in it) in [source] to [to] in [target], then checks that every
 * file arrived whole (the same size). A partial copy is deleted and the error thrown; [from] is never touched.
 */
fun copyChecked(source: Place, from: Path, target: Place, to: Path) {
    try {
        copyTree(source.fileSystem, from, target.fileSystem, to)
        for ((file, rel) in filesUnder(source.fileSystem, from)) {
            val size = source.fileSystem.metadata(file).size
            val copied = target.fileSystem.metadataOrNull(if (rel == null) to else to / rel)?.size
            check(size == copied) { "$file copied with $copied bytes of $size" }
        }
    } catch (e: Throwable) {
        runCatching { target.fileSystem.deleteRecursively(to) }
        throw e
    }
}

private fun copyTree(source: FileSystem, from: Path, target: FileSystem, to: Path) {
    if (source.metadata(from).isDirectory) {
        target.createDirectories(to)
        for (child in source.list(from)) copyTree(source, child, target, to / child.name)
    } else {
        to.parent?.let { target.createDirectories(it) }
        source.source(from).use { input -> target.sink(to).buffer().use { it.writeAll(input) } }
    }
}

/** Every file under [dir] (or [dir] itself when it is a file), with its path relative to [dir] (null for itself). */
private fun filesUnder(fs: FileSystem, dir: Path): List<Pair<Path, String?>> {
    if (!fs.metadata(dir).isDirectory) return listOf(dir to null)
    return fs.listRecursively(dir).filter { fs.metadata(it).isRegularFile }.map { it to it.relativeTo(dir).toString() }.toList()
}
