package com.chencohensolution.transcriptor.backup

import com.chencohensolution.shed.FilePage
import com.chencohensolution.shed.RemoteFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The part of the server's recordings the moments list holds now ([ServerMoments]): [files] newest first; [older] when
 * the server has older ones past them, [newer] when newer ones were let go from the window (scrolling up brings them
 * back). [loading] while a page is on its way; [failed] the last page that couldn't be read, in the storage's words.
 */
data class ServerWindow(
    val files: List<RemoteFile> = emptyList(),
    val older: Boolean = false,
    val newer: Boolean = false,
    val loading: Boolean = false,
    val failed: String? = null,
)

/**
 * The server's recordings for the moments list, read a page at a time as the user scrolls (owner's request, Nov 16,
 * 2026: no whole feed, and no years of recordings in memory). At most [maxPages] pages of [pageSize] are held: reading
 * older ones past that lets the newest page go, and reading newer ones again lets the oldest go, so going back years
 * keeps the same memory. [page] reads one page from the storage (shed's [com.chencohensolution.shed.Shed.page]).
 */
class ServerMoments(
    private val page: suspend (before: String?, after: String?, limit: Int) -> FilePage,
    private val pageSize: Int = 50,
    private val maxPages: Int = 6,
) {
    private val mutex = Mutex()
    private val pages = ArrayDeque<FilePage>()
    private val _window = MutableStateFlow(ServerWindow())
    val window: StateFlow<ServerWindow> = _window.asStateFlow()

    /**
     * Brings the window up to date: while it holds the newest recordings, the newest page is read again and put in
     * front, keeping the pages below it when they still meet (fewer than a page arrived); with none yet, the newest
     * page is read. A window scrolled away from the newest is left as it is.
     */
    suspend fun refresh() = read(wait = false) { refreshLocked() }

    private suspend fun refreshLocked() {
        if (pages.isNotEmpty() && pages.first().newer != null) return
        val fresh = page(null, null, pageSize)
        val first = pages.firstOrNull()
        val meets = first != null && fresh.files.any { f -> first.files.any { it.sha256 == f.sha256 } }
        if (!meets) {
            pages.clear()
            pages.addLast(fresh)
            return
        }
        // The new newest page, then what the old first page held below it.
        val seen = fresh.files.map { it.sha256 }.toSet()
        val lastFresh = fresh.files.last().sha256
        val below = first!!.files.dropWhile { it.sha256 != lastFresh }.drop(1).filter { it.sha256 !in seen }
        pages.removeFirst()
        pages.addFirst(FilePage(fresh.files + below, older = first.older, newer = null))
    }

    /** Reads the next older page, letting the newest page go past [maxPages]. */
    suspend fun older() = read(wait = false) {
        val cursor = pages.lastOrNull()?.older ?: return@read
        val next = page(cursor, null, pageSize)
        if (next.files.isEmpty()) {
            pages.addLast(pages.removeLast().copy(older = null))
            return@read
        }
        pages.addLast(next)
        while (pages.size > maxPages) pages.removeFirst()
    }

    /** Reads the page just newer than the window (after scrolling back up), letting the oldest page go past [maxPages]. */
    suspend fun newer() = read(wait = false) {
        val cursor = pages.firstOrNull()?.newer ?: return@read
        val next = page(null, cursor, pageSize)
        if (next.files.isEmpty()) {
            pages.addFirst(pages.removeFirst().copy(newer = null))
            return@read
        }
        pages.addFirst(next)
        while (pages.size > maxPages) pages.removeLast()
    }

    /** Lets every page go and reads the newest again: after what the server sends changed (calls shown, a file made private). */
    suspend fun reset() = read(wait = true) {
        pages.clear()
        refreshLocked()
    }

    /** Lets every page go (after unpairing). */
    suspend fun clear() = mutex.withLock {
        pages.clear()
        _window.value = ServerWindow()
    }

    /** The recording [sha256] when the window holds it. */
    fun find(sha256: String): RemoteFile? = _window.value.files.firstOrNull { it.sha256 == sha256 }

    /** Runs [work] on the pages, then shows them; a page already on its way makes it return at once unless [wait]. */
    private suspend fun read(wait: Boolean, work: suspend () -> Unit) {
        if (wait) mutex.lock() else if (!mutex.tryLock()) return // the list asks again when it needs more
        try {
            _window.value = _window.value.copy(loading = true)
            val failed = try {
                work()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            val files = pages.flatMap { it.files }.distinctBy { it.sha256 }
            _window.value = ServerWindow(
                files = files,
                older = pages.lastOrNull()?.older != null,
                newer = pages.firstOrNull()?.newer != null,
                loading = false,
                failed = failed,
            )
        } finally {
            mutex.unlock()
        }
    }
}
