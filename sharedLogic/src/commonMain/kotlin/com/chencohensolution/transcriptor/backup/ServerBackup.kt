package com.chencohensolution.transcriptor.backup

import com.chencohensolution.shed.FileAbout
import com.chencohensolution.shed.FileKind
import com.chencohensolution.shed.FileLocator
import com.chencohensolution.shed.Located
import com.chencohensolution.shed.RemoteFile
import com.chencohensolution.shed.RunResult
import com.chencohensolution.shed.Shed
import com.chencohensolution.shed.UploadState
import com.chencohensolution.shed.UploadStatus
import com.chencohensolution.shed.journal.JournalBackend
import com.chencohensolution.shed.journal.JournalState
import com.chencohensolution.shed.journal.PairingPreview
import com.chencohensolution.shed.journal.SoundRecordingsShown
import com.chencohensolution.transcriptor.notebook.AUDIO_AAC
import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.pendingEncode
import com.chencohensolution.transcriptor.notebook.summary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import com.chencohensolution.shed.BackendException
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.use
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import kotlin.math.abs

/** A moment's backup, as the app shows it. */
sealed interface MomentBackup {
    /** Not handed over: no server, or not finished yet. */
    data object None : MomentBackup

    /** Waiting to be sent, with bytes the server has so far. */
    data class Waiting(val sent: Long, val size: Long) : MomentBackup

    /** On the server. */
    data object BackedUp : MomentBackup

    /** The server won't take it, or its audio is gone from the phone. */
    data class Refused(val reason: String) : MomentBackup
}

/** Settings › Server: whether Audio Logs is paired, and how its backup stands. */
data class ServerState(
    /** Null when not paired: the app works as it always did, on the phone only. */
    val paired: JournalState.Paired? = null,
    val wifiOnly: Boolean = true,
    /** Moments waiting to be sent, and bytes left of them. */
    val waiting: Int = 0,
    val bytesLeft: Long = 0,
    /** Moments on the server. */
    val backedUp: Int = 0,
    /** Moments the server won't take, by title, with why. */
    val refused: List<Pair<String, String>> = emptyList(),
    /** Recordings on the server that aren't moments on this phone (recorded before, or on another phone). */
    val onServerOnly: List<RemoteFile> = emptyList(),
    /** What the last run of the queue ended with; null before one. */
    val lastRun: RunResult? = null,
    /**
     * Whether the server sends calls and voice messages to Audio Logs (off until the user turns them on); null before
     * the server was asked, or when it is too old to know.
     */
    val shown: SoundRecordingsShown? = null,
    /** Recordings on the server that aren't moments here and are tucked away (left out of [onServerOnly]). */
    val tuckedOnServer: Int = 0,
    /** Most bytes shed's cache keeps, and what it keeps now (as of [ServerBackup.refreshCache]). */
    val cacheLimitBytes: Long = Shed.DEFAULT_CACHE_LIMIT,
    val cacheBytes: Long = 0,
)

/** How marking a moment private ended ([ServerBackup.makePrivate]). */
sealed interface PrivateResult {
    /** On the server, private, and deleted from the phone. */
    data object Done : PrivateResult

    /** Not paired with a server: there is nowhere to keep it. */
    data object NotPaired : PrivateResult

    /** Being recorded, worked on or encoded: try again when it is done. */
    data object Busy : PrivateResult

    /** The server couldn't be reached, or doesn't hold it whole yet: nothing was deleted. */
    data class NotOnServer(val reason: String) : PrivateResult
}

@Serializable
private data class BackupFileV1(
    val v: Int = 1,
    val wifiOnly: Boolean = true,
    /** Moments handed to shed: id to the sha256 of its audio, and which audio file it was. */
    val moments: Map<String, HandedOver> = emptyMap(),
    /** Recordings of the server the user deleted here: not made moments again. */
    val dismissed: Set<String> = emptySet(),
    /** Most bytes shed's cache keeps of what was read from the server (Settings › Server backup). */
    val cacheLimitBytes: Long = Shed.DEFAULT_CACHE_LIMIT,
    /**
     * The server's recordings are read in pages ([ServerMoments]) since Nov 16, 2026: the moments made earlier for every
     * one of them, with no audio here, were let go once ([tidyServerMoments]).
     */
    val paged: Boolean = false,
    /**
     * The moment folders left without their data by an earlier build were cleared once, and their recordings
     * forgotten here so the server's copy shows again ([forgetBrokenMoments]).
     */
    val repaired: Boolean = false,
    /** The labels as they were at the last sync with the server's ([syncLabels]); null before the first. */
    val labelsBase: List<ServerLabel>? = null,
)

@Serializable
private data class HandedOver(
    val sha256: String,
    val audioFile: String,
    /** The sha256 of the moment's note last kept on the server ([ServerBackup.syncNotes]); null before one. */
    val note: String? = null,
)

/** What Free up space removes: the audio of moments the server holds ([ServerBackup.freeUpPlan]). */
sealed interface FreeUpRule {
    /** Every moment backed up. */
    data object Everything : FreeUpRule

    /** Moments recorded more than [months] months ago. */
    data class OlderThan(val months: Int) : FreeUpRule

    /** All but the newest moments' audio up to [bytes] (every moment with its audio here counts, backed up or not). */
    data class KeepNewest(val bytes: Long) : FreeUpRule
}

/** The moments a rule frees ([ids], oldest first), their audio's [bytes], and the dates they span. */
data class FreeUpPlan(val ids: List<String>, val bytes: Long, val firstMillis: Long? = null, val lastMillis: Long? = null)

/** How a Free up space run ended: [freed] moments, [bytes] freed, and why it stopped early ([stopped], null when it didn't). */
data class FreeUpResult(val freed: Int, val bytes: Long, val stopped: String? = null)

/**
 * Backing up Audio Logs' moments to the journal server through shed (docs/design.md, "Server backup"). The app works
 * the same without a server: until the user pairs, nothing is handed over and nothing goes out.
 *
 * Once paired, each finished moment's audio (FLAC, AAC or WAV, after encoding) is handed to shed by the moment's id
 * ([backUpFinished]); shed hashes it, keeps it in its queue and sends it in the background, finding it by the id where
 * it is then, since a finished moment moves to the lectures folder. What was handed over is kept in `backup.json`, so
 * a moment is hashed once. The server's recordings that are no moment here are listed too ([onServerOnly]).
 */
class ServerBackup(
    private val store: LectureStore,
    private val fileSystem: FileSystem,
    root: Path,
    /** The phone's offset from UTC at a time, for when a moment was recorded. */
    private val utcOffset: (Long) -> Long,
) {
    private val file = root / "backup.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val mutex = Mutex()
    private var kept: BackupFileV1 = load()
    private lateinit var shed: Shed
    private lateinit var journal: JournalBackend
    private val _state = MutableStateFlow(ServerState(wifiOnly = kept.wifiOnly))
    val state: StateFlow<ServerState> = _state.asStateFlow()

    /** Finds a handed-over moment's audio wherever the moment is now: shed asks when it sends it. */
    val locator = FileLocator { id ->
        val lecture = store.load(id) ?: return@FileLocator null
        Located(store.fileSystemOf(id), store.audioPath(lecture))
    }

    /**
     * The server's recordings that the moments list holds now, read a page at a time as the user scrolls: those that
     * are moments here already are left out by the list ([isMomentHere]).
     */
    val serverMoments = ServerMoments({ before, after, limit -> shed.page(FileKind.AUDIO, before, after, limit) })

    /** Whether the server's recording [sha256] is a moment here already (handed over from here, or opened). */
    fun isMomentHere(sha256: String): Boolean = sha256 in momentShas

    /** Whether the user deleted the server's recording [sha256] here: the list leaves it out. */
    fun isDismissed(sha256: String): Boolean = sha256 in kept.dismissed

    @kotlin.concurrent.Volatile
    private var momentShas: Set<String> = emptySet()

    /** Connects the shed made with [locator]; before any other call. */
    fun attach(shed: Shed, journal: JournalBackend) {
        this.shed = shed
        this.journal = journal
        shed.cacheLimitBytes = kept.cacheLimitBytes
        refresh()
    }

    val wifiOnly: Boolean get() = kept.wifiOnly

    fun setWifiOnly(on: Boolean) {
        save(kept.copy(wifiOnly = on))
        refresh()
    }

    // Pairing

    suspend fun preview(text: String): PairingPreview = journal.preview(text)

    suspend fun pair(text: String, deviceName: String) {
        journal.pair(text, deviceName)
        refresh()
    }

    /** Forgets the server. What was sent stays there; the phone keeps every moment. */
    suspend fun unpair() {
        journal.unpair()
        shown = null
        shed.forgetRemote()
        serverMoments.clear()
        save(kept.copy(moments = emptyMap(), dismissed = emptySet(), labelsBase = null))
        refresh()
    }

    // Backing up

    /**
     * Hands every finished moment not handed over yet to shed (files: not on the main thread). Skips the [busy] ones
     * (recording, open, a pass running), those waiting to be encoded, and everything while not paired. Returns how
     * many it handed over.
     */
    suspend fun backUpFinished(busy: Set<String>): Int {
        if (journal.state.value !is JournalState.Paired) return 0
        var handed = 0
        for (lecture in store.all()) {
            if (lecture.id in busy || lecture.pendingEncode) continue
            if (kept.moments[lecture.id]?.audioFile == lecture.audioFile) continue
            val fs = store.fileSystemOf(lecture.id)
            val audio = store.audioPath(lecture)
            if (!fs.exists(audio)) continue
            val sha = runCatching { shed.put(audio, aboutOf(lecture), ref = lecture.id, from = fs) }.getOrNull() ?: continue
            mutex.withLock { save(kept.copy(moments = kept.moments + (lecture.id to HandedOver(sha, lecture.audioFile)))) }
            handed++
        }
        refresh()
        return handed
    }

    /**
     * Marks the moment private (the owner's call, Nov 16, 2026): its audio is sent to the server if it isn't there
     * yet, the server checks it holds it whole, its transcript, notes and labels are kept with it there (a note,
     * [LectureStore.serverNote]), the server marks it private, and only then is it deleted from the phone. The server
     * never sends a private file to Audio Logs again, so it leaves the app; it stays on the server (journal on a phone
     * that may see private files shows it). Nothing is deleted when a step fails. Not on the main thread.
     */
    suspend fun makePrivate(lectureId: String, busy: Set<String>): PrivateResult {
        if (journal.state.value !is JournalState.Paired) return PrivateResult.NotPaired
        val lecture = store.load(lectureId) ?: return PrivateResult.Done
        if (lectureId in busy || lecture.pendingEncode) return PrivateResult.Busy
        return try {
            var sha = kept.moments[lectureId]?.takeIf { it.audioFile == lecture.audioFile }?.sha256
            if (sha == null) {
                val fs = store.fileSystemOf(lectureId)
                sha = shed.put(store.audioPath(lecture), aboutOf(lecture), ref = lectureId, from = fs)
                mutex.withLock { save(kept.copy(moments = kept.moments + (lectureId to HandedOver(sha, lecture.audioFile)))) }
            }
            if (shed.uploads.value.any { it.sha256 == sha && it.state == UploadState.WAITING }) {
                val run = shed.runUploads()
                refresh(lastRun = run)
            }
            shed.uploads.value.firstOrNull { it.sha256 == sha && it.state == UploadState.REFUSED }?.let {
                return PrivateResult.NotOnServer(it.refused ?: "refused")
            }
            if (!shed.verify(sha)) return PrivateResult.NotOnServer("not whole on the server yet")
            val note = buildJsonObject { put("audioLogs", json.parseToJsonElement(store.serverNote(lecture)).jsonObject) }
            if (!journal.setNote(sha, note)) return PrivateResult.NotOnServer("its notes weren't kept")
            journal.setPrivate(listOf(sha), true)
            store.delete(lectureId)
            mutex.withLock { save(kept.copy(moments = kept.moments - lectureId)) }
            refresh()
            PrivateResult.Done
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PrivateResult.NotOnServer(e.message ?: e::class.simpleName ?: "error")
        }
    }

    /**
     * Tells the server the moments were tucked away or brought back, for those it has (best effort: the phone's mark
     * is the truth for its own moments).
     */
    suspend fun setTucked(lectureIds: Collection<String>, tucked: Boolean) {
        if (journal.state.value !is JournalState.Paired) return
        val shas = lectureIds.mapNotNull { kept.moments[it]?.sha256 }
        if (shas.isEmpty()) return
        runCatching { journal.setTucked(shas, tucked) }
    }

    /** Asks the server whether it sends calls and voice messages to Audio Logs. */
    suspend fun refreshShown() {
        if (journal.state.value !is JournalState.Paired) return
        shown = runCatching { journal.soundRecordingsShown() }.getOrNull() ?: shown
        refresh()
    }

    /** Turns calls or voice messages on or off for Audio Logs on the server, then brings the server's list up to date. */
    suspend fun setShown(new: SoundRecordingsShown) {
        shown = journal.setSoundRecordingsShown(new)
        refresh()
        runCatching { serverMoments.reset() }
        refresh()
    }

    private var shown: SoundRecordingsShown? = null
    private var cacheBytes = 0L

    /** Sets the most shed's cache keeps; the oldest read goes first once it is over. */
    suspend fun setCacheLimit(bytes: Long) {
        save(kept.copy(cacheLimitBytes = bytes))
        shed.cacheLimitBytes = bytes
        refreshCache()
    }

    /** What shed's cache keeps now, for the Server page. Files: not on the main thread. */
    suspend fun refreshCache() {
        cacheBytes = runCatching { shed.cacheBytes() }.getOrDefault(cacheBytes)
        refresh()
    }

    // The labels, in Audio Logs' own data on the server

    /**
     * Brings the labels here and on the server together ([mergeLabels]): what the user added, renamed or deleted on
     * either phone since the last sync is kept, and both end up with the same list. On the server they are Audio Logs'
     * own data (`appData("labels")`), which no other app sees. True when the labels here changed. Network: not on the
     * main thread; quiet when the server can't be reached.
     */
    suspend fun syncLabels(labels: com.chencohensolution.transcriptor.notebook.LabelStore, now: Long): Boolean {
        if (!::shed.isInitialized || journal.state.value !is JournalState.Paired) return false
        var changedHere = false
        repeat(3) {
            val there = try {
                shed.appData(LABELS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return changedHere
            }
            val server = there.content?.let { runCatching { json.decodeFromString(ServerLabelsV1.serializer(), it).labels.map { l -> l.toLabel() } }.getOrNull() }.orEmpty()
            val local = labels.all()
            val merged = mergeLabels(kept.labelsBase?.map { it.toLabel() }, local, server)
            if (merged != local) {
                labels.replaceAll(merged, now)
                changedHere = true
            }
            if (merged != server || there.content == null) {
                val content = json.encodeToString(ServerLabelsV1.serializer(), ServerLabelsV1(labels = merged.map { it.toServer() }))
                val write = runCatching { shed.setAppData(LABELS, content, there.version) }.getOrNull() ?: return changedHere
                if (write is com.chencohensolution.shed.AppDataWrite.Conflict) return@repeat // changed on another phone meanwhile: merge again
            }
            save(kept.copy(labelsBase = merged.map { it.toServer() }))
            return changedHere
        }
        return changedHere
    }

    // The server's copy of each moment

    /**
     * Keeps each backed-up moment's transcript, notes, labels and speakers on the server, as the note of its audio
     * ([LectureStore.serverNote] under `audioLogs`), whenever they changed since they were last sent: a phone that
     * pairs later, or this one after Free up space, gets them back. Skips the [busy] moments and those not on the
     * server yet; stops at the first network failure. Returns how many it sent.
     */
    suspend fun syncNotes(busy: Set<String>): Int {
        if (journal.state.value !is JournalState.Paired) return 0
        var sent = 0
        for ((id, handed) in kept.moments) {
            if (id in busy) continue
            if (momentState(shed.uploads.value.firstOrNull { it.ref == id && it.sha256 == handed.sha256 }) != MomentBackup.BackedUp) continue
            val lecture = store.load(id) ?: continue
            if (lecture.pendingEncode) continue
            val note = noteOf(lecture)
            val hash = note.toString().encodeUtf8().sha256().hex()
            if (handed.note == hash) continue
            val kept = try {
                journal.setNote(handed.sha256, note)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: BackendException) {
                if (e.offline || e.unauthorized) break
                false
            } catch (_: Exception) {
                false
            }
            if (!kept) continue
            mutex.withLock { this.kept.moments[id]?.let { save(this.kept.copy(moments = this.kept.moments + (id to it.copy(note = hash)))) } }
            sent++
        }
        return sent
    }

    /** The moment's note on the server: Audio Logs' own (the server keeps each app's note apart). */
    private fun noteOf(lecture: Lecture): JsonObject = json.parseToJsonElement(store.serverNote(lecture)).jsonObject

    /**
     * Makes the server's recording [file] a moment here, when the user opens it (or changes it) from the list: from its
     * note when Audio Logs kept one, else as a moment not transcribed yet, in [defaultLanguage]. Its audio stays on the
     * server and plays from there ([openAudio]). The moment it already is, when it is one.
     */
    suspend fun momentHere(file: RemoteFile, defaultLanguage: String): Lecture {
        store.all().firstOrNull { it.serverSha256 == file.sha256 }?.let { return it }
        val lecture = momentOf(file, defaultLanguage)
        store.load(lecture.id)?.let { return it }
        store.save(lecture)
        mutex.withLock {
            save(kept.copy(moments = kept.moments + (lecture.id to HandedOver(file.sha256, lecture.audioFile, noteOf(lecture).toString().encodeUtf8().sha256().hex()))))
        }
        refresh()
        return lecture
    }

    /**
     * Once, after the update that reads the server's recordings in pages: lets go of the moments made earlier for every
     * recording of the server, those with no audio here whose words the server has as they are, since the list now
     * reads them from the server as the user scrolls. Moments changed here since stay. Not the [busy] ones.
     */
    suspend fun tidyServerMoments(busy: Set<String> = emptySet()) {
        if (kept.paged) return
        for (lecture in store.all()) {
            if (!lecture.id.startsWith("server-") || lecture.id in busy || store.hasAudio(lecture)) continue
            val handed = kept.moments[lecture.id] ?: continue
            if (handed.note != noteOf(lecture).toString().encodeUtf8().sha256().hex() && handed.note != legacyNoteHash(lecture)) continue
            store.delete(lecture.id)
            mutex.withLock { save(kept.copy(moments = kept.moments - lecture.id)) }
        }
        save(kept.copy(paged = true))
        refresh()
    }

    /**
     * Once: moment folders with no `lecture.json` (a delete cut short by an earlier build) can't be opened, yet their
     * recordings counted as here and the list left the server's copy out. Empty ones are deleted and every one of them
     * forgotten here, so the server's recording shows and opens again. Not the [busy] ones. Files: not on the main thread.
     */
    suspend fun forgetBrokenMoments(busy: Set<String> = emptySet()) {
        if (kept.repaired) return
        val broken = store.brokenFolders().filter { it !in busy }
        for (name in broken) runCatching { store.deleteBroken(name) }
        val gone = broken.filter { it in kept.moments && store.load(it) == null }.toSet()
        mutex.withLock { save(kept.copy(moments = kept.moments - gone, repaired = true)) }
        refresh()
    }

    /** The hash of a note as it was sent before each app had its own (wrapped in `audioLogs`). */
    private fun legacyNoteHash(lecture: Lecture): String =
        buildJsonObject { put("audioLogs", noteOf(lecture)) }.toString().encodeUtf8().sha256().hex()

    /** The server's recording [file] as the moments list shows it, before it is a moment here: its audio on the server. */
    fun summaryOf(file: RemoteFile, defaultLanguage: String): com.chencohensolution.transcriptor.notebook.LectureSummary =
        momentOf(file, defaultLanguage).summary().copy(audioOnServer = true)

    /** The moment the server's recording [file] is: from its note when the server keeps one, else a new one. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    internal fun momentOf(file: RemoteFile, defaultLanguage: String): Lecture {
        val extension = file.about.fileName.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "m4a"
        // Audio Logs' own note on the server; one sent before each app had its own is wrapped in "audioLogs".
        val restored = file.note
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.let { (it["audioLogs"] as? JsonObject ?: it).toString() }
            ?.let(store::fromServerNote)
        if (restored != null) {
            return restored.copy(serverSha256 = file.sha256, audioFile = "audio.$extension", pendingFlac = false, pendingAac = false, source = restored.source ?: file.about.source)
        }
        val started = listOfNotNull(file.about.takenAt, file.storedAt).firstNotNullOfOrNull {
            runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull()
        } ?: 0L
        return Lecture(
            id = "server-" + file.sha256.take(16),
            title = titleOf(file.about.fileName),
            language = defaultLanguage,
            startedAtMillis = started,
            durationMillis = file.about.durationMillis ?: 0,
            segments = emptyList(),
            notes = emptyList(),
            audioFile = "audio.$extension",
            pendingTranscript = true,
            tucked = file.tucked,
            serverSha256 = file.sha256,
            source = file.about.source,
        )
    }

    /**
     * The moment's audio on the server, to play as it streams ([com.chencohensolution.transcriptor.review.AudioStream]):
     * read through shed by blocks, kept in its cache. Null when the server doesn't hold it (or it is on the phone).
     */
    fun streamOf(lecture: Lecture): com.chencohensolution.transcriptor.review.AudioStream? {
        if (!::shed.isInitialized || journal.state.value !is JournalState.Paired) return null
        val sha = lecture.serverSha256 ?: kept.moments[lecture.id]?.sha256 ?: return null
        var reader: com.chencohensolution.shed.ShedReader? = null
        val open: suspend () -> com.chencohensolution.shed.ShedReader = { reader ?: shed.open(sha).also { reader = it } }
        return com.chencohensolution.transcriptor.review.AudioStream(
            size = { open().size },
            read = { position, length -> open().read(position, length) },
        )
    }

    /**
     * Brings the moment's audio back to the phone from the server, through shed (with [progress]), when it is there
     * only: before it is played or transcribed. True when the audio is on the phone now. Not on the main thread.
     */
    suspend fun bringAudio(lectureId: String, progress: (got: Long, of: Long) -> Unit = { _, _ -> }): Boolean {
        val lecture = store.load(lectureId) ?: return false
        if (store.hasAudio(lecture)) return true
        val sha = lecture.serverSha256 ?: kept.moments[lectureId]?.sha256 ?: return false
        val fetched = shed.fetch(sha, progress)
        val fs = store.fileSystemOf(lectureId)
        val to = store.audioPath(lecture)
        val temp = to.parent!! / "${to.name}.part"
        fs.createDirectories(to.parent!!)
        fileSystem.source(fetched).use { from -> fs.sink(temp).buffer().use { it.writeAll(from) } }
        fs.atomicMove(temp, to)
        return true
    }

    /**
     * What Free up space would remove with [rule] at [now]: the audio of finished moments here that are on the server
     * as they are now, oldest first (not the [busy] ones, nor one waiting to be encoded or sent). Their transcripts,
     * notes and labels stay. Files: not on the main thread.
     */
    fun freeUpPlan(rule: FreeUpRule, now: Long, busy: Set<String> = emptySet()): FreeUpPlan {
        val withAudio = store.all().filter { store.hasAudio(it) }.sortedBy { it.startedAtMillis }
        val sizes = withAudio.associate { it.id to (store.audioBytes(it) ?: 0L) }
        val candidates = withAudio.filter { lecture ->
            val handed = kept.moments[lecture.id]
            lecture.id !in busy && !lecture.pendingEncode && handed != null && handed.audioFile == lecture.audioFile &&
                momentState(shed.uploads.value.firstOrNull { it.ref == lecture.id && it.sha256 == handed.sha256 }) == MomentBackup.BackedUp
        }
        val chosen = when (rule) {
            FreeUpRule.Everything -> candidates
            is FreeUpRule.OlderThan -> candidates.filter { it.startedAtMillis < now - rule.months * 30L * 86_400_000L }
            is FreeUpRule.KeepNewest -> {
                var kept = 0L
                val keep = mutableSetOf<String>()
                for (lecture in withAudio.reversed()) {
                    if (kept + (sizes[lecture.id] ?: 0) > rule.bytes) break
                    kept += sizes[lecture.id] ?: 0
                    keep += lecture.id
                }
                candidates.filter { it.id !in keep }
            }
        }
        return FreeUpPlan(chosen.map { it.id }, chosen.sumOf { sizes[it.id] ?: 0L }, chosen.firstOrNull()?.startedAtMillis, chosen.lastOrNull()?.startedAtMillis)
    }

    /**
     * Removes the audio of [ids] from the phone, each only once the phone's copy hashes to what was sent and the
     * server checked it holds that whole (`/verify`); the moment keeps its transcript, notes and labels and remembers
     * the audio's sha256, so opening it brings the audio back ([bringAudio]). An unreachable server stops the run:
     * nothing unchecked is removed. Files and network: not on the main thread.
     */
    suspend fun freeUp(ids: List<String>, busy: Set<String> = emptySet(), progress: (done: Int, of: Int) -> Unit = { _, _ -> }): FreeUpResult {
        var freed = 0
        var bytes = 0L
        for ((i, id) in ids.withIndex()) {
            progress(i, ids.size)
            val lecture = store.load(id) ?: continue
            val handed = kept.moments[id] ?: continue
            if (id in busy || lecture.pendingEncode || handed.audioFile != lecture.audioFile || !store.hasAudio(lecture)) continue
            val fs = store.fileSystemOf(id)
            val audio = store.audioPath(lecture)
            val hash = HashingSink.sha256(blackholeSink()).also { sink -> fs.source(audio).buffer().use { it.readAll(sink) } }.hash.hex()
            if (hash != handed.sha256) continue
            val held = try {
                shed.verify(handed.sha256)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return FreeUpResult(freed, bytes, e.message ?: "the server didn't answer")
            }
            if (!held) continue
            val size = store.audioBytes(lecture) ?: 0L
            store.save(lecture.copy(serverSha256 = handed.sha256))
            store.deleteAudio(lecture)
            freed++
            bytes += size
        }
        progress(ids.size, ids.size)
        return FreeUpResult(freed, bytes)
    }

    /** The user deleted these moments here: the server's recordings they were aren't made moments again. */
    fun dismiss(lectureIds: Collection<String>) {
        val shas = lectureIds.mapNotNull { id -> store.load(id)?.serverSha256 ?: kept.moments[id]?.sha256 }
        if (shas.isEmpty()) return
        save(kept.copy(dismissed = kept.dismissed + shas, moments = kept.moments - lectureIds.toSet()))
    }

    /** One pass of shed's queue, from the app's background work. */
    suspend fun runUploads(): RunResult = shed.runUploads().also { result ->
        refresh(lastRun = result)
        if (result is RunResult.Done) runCatching { syncNotes(emptySet()) }
    }

    /** The server's recordings the list holds brought up to date (the newest page, while the list is at the top). */
    suspend fun syncServerList() {
        if (journal.state.value !is JournalState.Paired) return
        if (shown == null) shown = runCatching { journal.soundRecordingsShown() }.getOrNull()
        serverMoments.refresh()
        refresh()
    }

    /** Waiting moments, for the app to decide whether to start its background work. */
    fun waiting(): Int = if (::shed.isInitialized) shed.waiting() else 0

    /** [lectureId]'s backup. */
    fun stateOf(lectureId: String): MomentBackup {
        val handed = kept.moments[lectureId] ?: return MomentBackup.None
        val status = shed.uploads.value.firstOrNull { it.ref == lectureId && it.sha256 == handed.sha256 }
        return momentState(status)
    }

    /** The sha256 the server knows the moment's audio by, once handed over. */
    fun sha256Of(lectureId: String): String? = kept.moments[lectureId]?.sha256

    private fun momentState(status: UploadStatus?): MomentBackup = when (status?.state) {
        // Done files leave shed's list after a week: handed over and not listed is done.
        null, UploadState.DONE -> MomentBackup.BackedUp
        UploadState.WAITING -> MomentBackup.Waiting(status.sent, status.size)
        UploadState.REFUSED -> MomentBackup.Refused(status.refused ?: "")
    }

    /** The state again from shed, the pairing and what was handed over. */
    fun refresh(lastRun: RunResult? = _state.value.lastRun) {
        if (!::shed.isInitialized) return
        val paired = journal.state.value as? JournalState.Paired
        val byMoment = kept.moments.mapValues { (id, h) ->
            momentState(shed.uploads.value.firstOrNull { it.ref == id && it.sha256 == h.sha256 })
        }
        val waiting = byMoment.values.filterIsInstance<MomentBackup.Waiting>()
        val mine = kept.moments.values.map { it.sha256 }.toSet()
        momentShas = mine
        val others = if (paired == null) emptyList() else serverMoments.window.value.files.filter { it.sha256 !in mine && it.sha256 !in kept.dismissed }
        _state.value = ServerState(
            paired = paired,
            wifiOnly = kept.wifiOnly,
            waiting = waiting.size,
            bytesLeft = waiting.sumOf { it.size - it.sent },
            backedUp = byMoment.values.count { it == MomentBackup.BackedUp },
            refused = byMoment.mapNotNull { (id, s) -> (s as? MomentBackup.Refused)?.let { (store.load(id)?.title ?: id) to it.reason } },
            onServerOnly = others.filter { !it.tucked },
            lastRun = lastRun,
            shown = if (paired == null) null else shown,
            tuckedOnServer = others.count { it.tucked },
            cacheLimitBytes = kept.cacheLimitBytes,
            cacheBytes = cacheBytes,
        )
    }

    /** What the server is told about a moment's audio. */
    internal fun aboutOf(lecture: Lecture): FileAbout {
        val extension = lecture.audioFile.substringAfterLast('.', "wav")
        val mime = when (lecture.audioFile) {
            AUDIO_FLAC -> "audio/flac"
            AUDIO_AAC -> "audio/aac"
            else -> "audio/wav"
        }
        val name = lecture.title.ifBlank { lecture.id }.replace(Regex("""[\\/:*?"<>|\[\]]"""), "-").take(80)
        return FileAbout(
            fileName = "$name.$extension",
            mimeType = mime,
            kind = FileKind.AUDIO,
            takenAt = rfc3339(lecture.startedAtMillis, utcOffset(lecture.startedAtMillis)),
            durationMillis = lecture.durationMillis,
            source = "recording",
            folder = "Audio Logs",
        )
    }

    private fun load(): BackupFileV1 = runCatching {
        if (!fileSystem.exists(file)) BackupFileV1()
        else json.decodeFromString(BackupFileV1.serializer(), fileSystem.read(file) { readUtf8() })
    }.getOrElse { BackupFileV1() }

    private fun save(new: BackupFileV1) {
        kept = new
        fileSystem.createDirectories(file.parent!!)
        val temp = file.parent!! / "backup.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(BackupFileV1.serializer(), new)) }
        fileSystem.atomicMove(temp, file)
    }
}

/** [millis] as RFC 3339 at [offsetMillis] from UTC: `2026-11-16T12:05:09+02:00`. */
internal fun rfc3339(millis: Long, offsetMillis: Long): String {
    val local = millis + offsetMillis
    val days = local.floorDiv(86_400_000L)
    val inDay = local.mod(86_400_000L)
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    val seconds = inDay / 1000
    val offsetMinutes = offsetMillis / 60_000
    val sign = if (offsetMinutes < 0) "-" else "+"
    fun two(n: Long) = n.toString().padStart(2, '0')
    return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}T${two(seconds / 3600)}:${two(seconds / 60 % 60)}:${two(seconds % 60)}" +
        "$sign${two(abs(offsetMinutes) / 60)}:${two(abs(offsetMinutes) % 60)}"
}

private val SAMSUNG_CALL = Regex("""^(?:Call recording|הקלטת שיחה|שיחה) (.+)_\d{6}_\d{6}$""")

/** A moment's title from a server recording's file name: whom a Samsung call was with, else the name without its extension. */
internal fun titleOf(fileName: String): String {
    val base = fileName.substringBeforeLast('.').trim()
    return SAMSUNG_CALL.find(base)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: base.ifEmpty { fileName }
}

/** The name of the labels in Audio Logs' own data on the server. */
private const val LABELS = "labels"
