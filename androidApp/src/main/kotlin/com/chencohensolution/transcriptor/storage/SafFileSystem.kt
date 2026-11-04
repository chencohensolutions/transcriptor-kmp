package com.chencohensolution.transcriptor.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import okio.FileHandle
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import java.util.concurrent.ConcurrentHashMap

/** Where the lectures folder's files are, as paths: every path under it starts here, so it can't be mistaken for one in the app. */
val LIBRARY_ROOT = "/library".toPath()

/**
 * The lectures folder the user picked outside the app ([tree], from `ACTION_OPEN_DOCUMENT_TREE`, with a persisted
 * permission) as a file system: [LIBRARY_ROOT] is the folder, and a path below it names its documents by name. Built on
 * Android's documents provider, so it reaches the folder after a reinstall once it is picked again, with no "all files"
 * permission. Files are read and written as streams ([openFd] for the audio, which is read with seeks); moves within a
 * folder are renames, between folders the provider's move. Document ids are cached by path.
 */
class SafFileSystem(private val resolver: ContentResolver, private val tree: Uri) : FileSystem() {
    // The picked tree itself, or a folder in it (a document URI under the tree: the Transcriptor folder made in it).
    private val rootId = runCatching { DocumentsContract.getDocumentId(tree) }.getOrElse { DocumentsContract.getTreeDocumentId(tree) }
    private val ids = ConcurrentHashMap<Path, String>()

    private data class Doc(val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
        val isDirectory get() = mime == Document.MIME_TYPE_DIR
    }

    /** The folder's name, as the user knows it ("Transcriptor"). */
    val name: String get() = doc(rootId)?.name ?: ""

    /** The document id of a file or folder in it, for a URI to it. */
    fun documentId(path: Path): String = idOf(path) ?: throw FileNotFoundException("$path")

    /** A file of the folder opened for the system's media readers ("r"), which seek. */
    fun openFd(file: Path, mode: String = "r"): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri(idOf(file) ?: throw FileNotFoundException("$file")), mode) ?: throw IOException("can't open $file")

    override fun canonicalize(path: Path): Path = path.normalized()

    override fun metadataOrNull(path: Path): FileMetadata? {
        val id = idOf(path) ?: return null
        val doc = doc(id) ?: run { forget(path); return null }
        return FileMetadata(
            isRegularFile = !doc.isDirectory, isDirectory = doc.isDirectory,
            size = if (doc.isDirectory) null else doc.size, lastModifiedAtMillis = doc.modified,
        )
    }

    override fun list(dir: Path): List<Path> = listOrNull(dir) ?: throw FileNotFoundException("$dir")

    override fun listOrNull(dir: Path): List<Path>? {
        val id = idOf(dir) ?: return null
        return children(id).map { child -> (dir.normalized() / child.name).also { ids[it] = child.id } }.sorted()
    }

    override fun source(file: Path): Source =
        (resolver.openInputStream(uri(idOf(file) ?: throw FileNotFoundException("$file"))) ?: throw IOException("can't read $file")).source()

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val existing = idOf(file)
        if (existing != null && mustCreate) throw IOException("$file exists")
        val id = existing ?: create(file, "application/octet-stream")
        return (resolver.openOutputStream(uri(id), "wt") ?: throw IOException("can't write $file")).sink()
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        val existing = idOf(file)
        if (existing == null && mustExist) throw FileNotFoundException("$file")
        val id = existing ?: create(file, "application/octet-stream")
        return (resolver.openOutputStream(uri(id), "wa") ?: throw IOException("can't write $file")).sink()
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        if (idOf(dir) != null) {
            if (mustCreate) throw IOException("$dir exists")
            return
        }
        create(dir, Document.MIME_TYPE_DIR)
    }

    /** A rename in one folder, the provider's move between folders; what [target] was is replaced. */
    override fun atomicMove(source: Path, target: Path) {
        val from = source.normalized()
        val to = target.normalized()
        val id = idOf(from) ?: throw FileNotFoundException("$from")
        idOf(to)?.let { DocumentsContract.deleteDocument(resolver, uri(it)); forget(to) }
        var moved = uri(id)
        if (from.parent != to.parent) {
            val fromParent = idOf(from.parent!!) ?: throw FileNotFoundException("${from.parent}")
            val toParent = idOf(to.parent!!) ?: throw FileNotFoundException("${to.parent}")
            moved = DocumentsContract.moveDocument(resolver, moved, uri(fromParent), uri(toParent)) ?: throw IOException("can't move $from to $to")
        }
        if (from.name != to.name) moved = DocumentsContract.renameDocument(resolver, moved, to.name) ?: throw IOException("can't rename $from to $to")
        forget(from)
        ids[to] = DocumentsContract.getDocumentId(moved)
    }

    override fun delete(path: Path, mustExist: Boolean) {
        val id = idOf(path) ?: if (mustExist) throw FileNotFoundException("$path") else return
        if (!DocumentsContract.deleteDocument(resolver, uri(id))) throw IOException("can't delete $path")
        forget(path)
    }

    override fun openReadOnly(file: Path): FileHandle = throw UnsupportedOperationException("the lectures folder is read as streams")
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle = throw UnsupportedOperationException("the lectures folder is written as streams")
    override fun createSymlink(source: Path, target: Path) = throw UnsupportedOperationException("no links in the lectures folder")

    /** The document [path] names, by its parent's children; null when there is none. */
    private fun idOf(path: Path): String? {
        val p = path.normalized()
        if (p == LIBRARY_ROOT) return rootId
        require(p.toString().startsWith("$LIBRARY_ROOT/")) { "$p is not in the lectures folder" }
        ids[p]?.let { return it }
        val parent = idOf(p.parent ?: return null) ?: return null
        val child = children(parent).firstOrNull { it.name == p.name } ?: return null
        ids[p] = child.id
        return child.id
    }

    /** A new document at [path], with that exact name (the provider may add a number or an extension: renamed back). */
    private fun create(path: Path, mime: String): String {
        val parent = idOf(path.parent!!) ?: throw FileNotFoundException("${path.parent}")
        var made = DocumentsContract.createDocument(resolver, uri(parent), mime, path.name) ?: throw IOException("can't create $path")
        if (doc(DocumentsContract.getDocumentId(made))?.name != path.name) {
            made = DocumentsContract.renameDocument(resolver, made, path.name) ?: throw IOException("can't name $path")
        }
        return DocumentsContract.getDocumentId(made).also { ids[path.normalized()] = it }
    }

    private fun forget(path: Path) {
        val p = path.normalized()
        ids.keys.removeIf { it == p || it.toString().startsWith("$p/") }
    }

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun children(parentId: String) = query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId))

    private fun doc(id: String) = query(uri(id)).firstOrNull()

    private fun query(uri: Uri): List<Doc> = resolver.query(
        uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED),
        null, null, null,
    )?.use { c ->
        buildList { while (c.moveToNext()) add(Doc(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))) }
    } ?: emptyList()
}
