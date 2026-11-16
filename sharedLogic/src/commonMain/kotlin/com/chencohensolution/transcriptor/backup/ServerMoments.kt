package com.chencohensolution.transcriptor.backup

import com.chencohensolution.shed.RemoteFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The server's recordings for the moments list ([ServerMoments]): [files] newest first. [loading] while the first sync
 * runs and nothing is listed yet; [failed] the last sync that couldn't finish, in the storage's words. [older] and
 * [newer] stay false: the whole list is here.
 */
data class ServerWindow(
    val files: List<RemoteFile> = emptyList(),
    val older: Boolean = false,
    val newer: Boolean = false,
    val loading: Boolean = false,
    val failed: String? = null,
)

/**
 * The server's recordings for the moments list, from a copy kept on the phone (owner's call, Nov 16, 2026, replacing
 * pages read as the user scrolled, which made the list jump): shed's list of what the server holds (`remote.json`),
 * brought up to date from the server's change feed of sound recordings only. The list shows it at once on start, all
 * of it, and [refresh] reads only what changed since the last time.
 *
 * [files] is that list as it is now (shed's [com.chencohensolution.shed.Shed.remote]); [sync] brings it up to date
 * ([com.chencohensolution.shed.Shed.sync]) and [forget] drops it ([com.chencohensolution.shed.Shed.forgetRemote]).
 */
class ServerMoments(
    private val files: () -> List<RemoteFile>,
    private val sync: suspend () -> Unit,
    private val forget: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val _window = MutableStateFlow(ServerWindow())
    val window: StateFlow<ServerWindow> = _window.asStateFlow()

    @kotlin.concurrent.Volatile
    private var bySha: Map<String, RemoteFile> = emptyMap()

    /** Shows the list as kept on the phone, before the server answers (at start). */
    fun show() = publish(loading = false, failed = _window.value.failed)

    /** Brings the list up to date with what changed on the server; a sync already running makes it return at once. */
    suspend fun refresh() {
        if (!mutex.tryLock()) return
        try {
            publish(loading = true, failed = null)
            val failed = try {
                sync()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            publish(loading = false, failed = failed)
        } finally {
            mutex.unlock()
        }
    }

    /** Brings the list up to date after what the server sends changed (calls shown, a file made private): the feed sends the difference. */
    suspend fun reset() = refresh()

    /** Forgets the list (after unpairing). */
    suspend fun clear() = mutex.withLock {
        runCatching { forget() }
        bySha = emptyMap()
        _window.value = ServerWindow()
    }

    /** The recording [sha256] when the list holds it. */
    fun find(sha256: String): RemoteFile? = bySha[sha256]

    private fun publish(loading: Boolean, failed: String?) {
        val all = files().map { it to startedAt(it) }.sortedByDescending { it.second }.map { it.first }
        bySha = all.associateBy { it.sha256 }
        _window.value = ServerWindow(files = all, loading = loading && all.isEmpty(), failed = failed)
    }

    private companion object {
        /** When the recording was made, else when the server got it. */
        @OptIn(kotlin.time.ExperimentalTime::class)
        fun startedAt(file: RemoteFile): Long = listOfNotNull(file.about.takenAt, file.storedAt).firstNotNullOfOrNull {
            runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull()
        } ?: 0L
    }
}
