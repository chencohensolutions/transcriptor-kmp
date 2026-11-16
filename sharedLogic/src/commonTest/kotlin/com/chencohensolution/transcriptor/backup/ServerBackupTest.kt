package com.chencohensolution.transcriptor.backup

import com.chencohensolution.shed.BackendException
import com.chencohensolution.shed.Capabilities
import com.chencohensolution.shed.ChangePage
import com.chencohensolution.shed.FileAbout
import com.chencohensolution.shed.PieceAnswer
import com.chencohensolution.shed.RemoteFile
import com.chencohensolution.shed.RunResult
import com.chencohensolution.shed.Shed
import com.chencohensolution.shed.ShedBackend
import com.chencohensolution.shed.UploadStart
import com.chencohensolution.shed.journal.DeviceKey
import com.chencohensolution.shed.journal.DeviceKeys
import com.chencohensolution.shed.journal.JournalBackend
import com.chencohensolution.shed.journal.JournalClients
import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.Label
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Place
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.chencohensolution.shed.FileKind
import com.chencohensolution.shed.FilePage
import com.chencohensolution.shed.AppData
import com.chencohensolution.shed.AppDataWrite
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Segment
import okio.ByteString.Companion.toByteString

class ServerBackupTest {
    private val fs = FakeFileSystem()
    private val root = "/app".toPath()
    private val store = LectureStore(fs, root)
    private val server = MemoryStorage()

    private fun moment(id: String, audio: String = "the sound of $id", pendingFlac: Boolean = false): Lecture {
        val lecture = Lecture(id, "Class $id", "he", startedAtMillis = 1_763_287_509_000, durationMillis = 60_000, segments = emptyList(), notes = emptyList(), audioFile = AUDIO_FLAC, pendingFlac = pendingFlac)
        store.save(lecture)
        fs.write(store.audioPath(lecture)) { writeUtf8(audio) }
        return lecture
    }

    private fun backup(paired: Boolean): ServerBackup {
        val backup = ServerBackup(store, fs, root) { 2 * 3_600_000L }
        val shedDir = root / "shed"
        if (paired) {
            fs.createDirectories(shedDir / "journal")
            fs.write(shedDir / "journal" / "pairing.json") {
                writeUtf8("""{"v":1,"server":"https://home:56398","fingerprint":"00","alias":"shed-audio-logs","user":"hen","deviceName":"Pixel","expiresAt":"2027-11-16T00:00:00Z"}""")
            }
        }
        val journal = JournalBackend("audio-logs", fs, shedDir / "journal", PairedKeys, JournalClients { _, _ -> error("no network in tests") })
        backup.attach(Shed(server, fs, shedDir, locator = backup.locator), journal)
        return backup
    }

    @Test
    fun withoutAServerNothingIsHandedOver() = runTest {
        moment("a")
        val backup = backup(paired = false)
        assertEquals(0, backup.backUpFinished(emptySet()))
        assertEquals(null, backup.state.value.paired)
        assertEquals(MomentBackup.None, backup.stateOf("a"))
    }

    @Test
    fun finishedMomentsAreSentOnceAndFoundAfterTheyMoveToTheLibrary() = runTest {
        moment("a")
        moment("recording", pendingFlac = false)
        moment("encoding", pendingFlac = true)
        val backup = backup(paired = true)
        assertEquals(1, backup.backUpFinished(busy = setOf("recording")))
        assertEquals(0, backup.backUpFinished(busy = setOf("recording")))
        assertIs<MomentBackup.Waiting>(backup.stateOf("a"))

        // The finished moment moves to the lectures folder before the upload runs.
        val library = Place(fs, "/library".toPath())
        store.library = library
        fs.createDirectories(library.moments)
        fs.atomicMove(store.local.folder("a"), library.folder("a"))

        assertEquals(RunResult.Done(sent = 1, refused = 0), backup.runUploads())
        assertEquals(MomentBackup.BackedUp, backup.stateOf("a"))
        assertEquals("the sound of a", server.files[backup.sha256Of("a")]?.decodeToString())
        assertEquals(1, backup.state.value.backedUp)
        assertEquals(0, backup.state.value.waiting)
    }

    @Test
    fun callsInThePickedFolderAreSentOnceAsCallsAndNothingIsDeleted() = runTest {
        val calls = "/sdcard/Recordings/Call".toPath()
        fs.createDirectories(calls / "old")
        fs.write(calls / "Call recording Dana_251116_101500.m4a") { writeUtf8("call one") }
        fs.write(calls / "old" / "Call recording Avi_240101_090000.m4a") { writeUtf8("call two") }
        fs.write(calls / ".nomedia") {}
        fs.write(calls / "notes.txt") { writeUtf8("not a call") }
        val backup = backup(paired = true)
        backup.useCallsFolder(fs, calls)
        assertEquals(0, backup.backUpCalls(), "not picked yet")
        backup.chooseCallsFolder("content://tree/Call", "Call")
        assertEquals(2, backup.backUpCalls())
        assertEquals(0, backup.backUpCalls(), "handed over once")
        assertEquals(2, backup.state.value.callsWaiting)
        assertEquals(RunResult.Done(sent = 2, refused = 0), backup.runUploads())
        assertEquals(setOf("call one", "call two"), server.files.values.map { it.decodeToString() }.toSet())
        assertEquals(listOf("call", "call"), server.abouts.values.map { it.source })
        assertEquals("Call", server.abouts.values.first().folder)
        assertTrue(fs.exists(calls / "old" / "Call recording Avi_240101_090000.m4a"), "the phone keeps its calls")
        assertEquals("Call", backup.state.value.callsFolder)
        assertEquals(2, backup.state.value.callsHanded)
        // A new call later: only it is sent.
        fs.write(calls / "Call recording Dana_251117_101500.m4a") { writeUtf8("call three") }
        assertEquals(1, backup.backUpCalls())
        backup.chooseCallsFolder(null, null)
        assertEquals(0, backup.backUpCalls())
        assertEquals(null, backup.state.value.callsFolder)
    }

    @Test
    fun aMomentIsDeletedOnlyOnceTheServerKeepsItPrivate() = runTest {
        moment("a")
        assertEquals(PrivateResult.NotPaired, backup(paired = false).makePrivate("a", emptySet()))
        val backup = backup(paired = true)
        assertEquals(PrivateResult.Busy, backup.makePrivate("a", busy = setOf("a")))
        // The audio reaches the server, but the server can't be asked to keep its notes and mark it: nothing is deleted.
        assertIs<PrivateResult.NotOnServer>(backup.makePrivate("a", emptySet()))
        assertEquals("the sound of a", server.files[backup.sha256Of("a")]?.decodeToString())
        assertEquals("Class a", store.load("a")?.title)
    }

    @Test
    fun theServerKeepsTheMomentsWordsWithoutWordTimes() {
        val lecture = moment("a").copy(
            segments = listOf(com.chencohensolution.transcriptor.notebook.Segment("s1", 0, 900, "שלום לכולם", "m",
                words = listOf(com.chencohensolution.transcriptor.notebook.Word(0, "שלום")))),
            notes = listOf(com.chencohensolution.transcriptor.notebook.Note("n1", 300, "exam")),
            tucked = true,
        )
        val note = store.serverNote(lecture)
        kotlin.test.assertTrue("שלום לכולם" in note && "exam" in note && "\"tucked\": true" in note, note)
        kotlin.test.assertFalse("\"words\"" in note && "\"atMs\": 0" in note, note)
    }

    @Test
    fun theServersRecordingsAreReadByPagesAndBecomeMomentsWhenOpened() = runTest {
        val backup = backup(paired = true)
        val call = "the sound of a call".encodeToByteArray()
        val callSha = call.toByteString().sha256().hex()
        server.files[callSha] = call
        val callFile = RemoteFile(callSha, call.size.toLong(), FileAbout("Call recording אבא_250206_142813.m4a", "audio/mp4", FileKind.AUDIO,
            takenAt = "2025-02-06T14:28:13+02:00", durationMillis = 61_000, source = "call"))
        // A moment another phone backed up, with its transcript kept on the server as Audio Logs' own note.
        val earlier = Lecture("lecture-5", "Psychology", "he", 1_700_000_000_000, 90_000, listOf(Segment("s1", 0, 900, "שלום", "m")), listOf(Note("n1", 10, "exam")), audioFile = AUDIO_FLAC)
        server.files["e".repeat(64)] = "flac".encodeToByteArray()
        val earlierFile = RemoteFile("e".repeat(64), 4, FileAbout("Psychology.flac", "audio/flac", FileKind.AUDIO, source = "recording"), note = store.serverNote(earlier))
        server.listed += listOf(callFile, earlierFile)
        backup.syncServerList()
        assertEquals(listOf(callSha, "e".repeat(64)), backup.serverMoments.window.value.files.map { it.sha256 })
        assertEquals(emptyList(), store.all(), "nothing is saved here until opened")

        val made = backup.momentHere(callFile, "he")
        assertEquals("אבא", made.title)
        assertEquals("call", made.source)
        assertEquals(true, made.pendingTranscript)
        assertEquals(1_738_844_893_000, made.startedAtMillis)
        assertEquals(false, store.hasAudio(made))
        assertEquals(made.id, backup.momentHere(callFile, "he").id, "made once")
        // It plays as it streams.
        val stream = backup.streamOf(made)!!
        assertEquals(call.size.toLong(), stream.size())
        assertEquals("sound", stream.read(4, 5).decodeToString())
        // Transcribing brings the whole file.
        assertTrue(backup.bringAudio(made.id))
        assertEquals("the sound of a call", fs.read(store.audioPath(made)) { readUtf8() })

        val restored = backup.momentHere(earlierFile, "he")
        assertEquals("lecture-5", restored.id)
        assertEquals(listOf("שלום"), restored.segments.map { it.text })
        assertEquals(listOf("exam"), restored.notes.map { it.text })
        // A note sent before each app had its own is read too.
        val wrapped = earlierFile.copy(sha256 = "d".repeat(64), note = "{\"audioLogs\": ${store.serverNote(earlier.copy(id = "lecture-6"))}}")
        assertEquals("lecture-6", backup.momentOf(wrapped, "he").id)
    }

    @Test
    fun theServersListIsShownWholeNewestFirstFromWhatThePhoneKeeps() = runTest {
        fun file(i: Int, taken: String?) = RemoteFile(i.toString().padStart(64, '0'), 1, FileAbout("$i.m4a", "audio/mp4", FileKind.AUDIO, takenAt = taken), storedAt = "2026-01-0${i}T10:00:00Z")
        var kept = listOf(file(1, "2025-03-01T10:00:00+02:00"), file(2, null), file(3, "2024-01-01T10:00:00Z"))
        var forgot = false
        val moments = ServerMoments({ kept }, { kept = kept + file(4, "2026-11-16T10:00:00Z") }, { forgot = true; kept = emptyList() })
        fun shown() = moments.window.value.files.map { it.about.fileName.substringBefore('.').toInt() }
        moments.show()
        assertEquals(listOf(2, 1, 3), shown(), "as kept, before the server answers; by when each was made, else stored")
        moments.refresh()
        assertEquals(listOf(4, 2, 1, 3), shown())
        assertEquals(false, moments.window.value.older || moments.window.value.loading)
        assertEquals(1L, moments.find("4".padStart(64, '0'))?.size)
        moments.clear()
        assertTrue(forgot && moments.window.value.files.isEmpty())
    }

    @Test
    fun labelsMergeWithTheServersOwnCopy() {
        val a = Label("a", "Psychology", 0)
        val b = Label("b", "Exam", 1)
        val c = Label("c", "Family", 2)
        // First sync: both sides' labels.
        assertEquals(listOf(a, c), mergeLabels(null, listOf(a), listOf(c)))
        // Renamed here, deleted on the server, added there: each kept.
        val renamed = a.copy(name = "Psych")
        assertEquals(listOf(renamed, c), mergeLabels(listOf(a, b), listOf(renamed, b), listOf(a, c)))
        // Deleted here: gone, though the server still has it unchanged.
        assertEquals(listOf(a), mergeLabels(listOf(a, b), listOf(a), listOf(a, b)))
    }

    @Test
    fun labelsGoToTheServerAsAudioLogsOwnData() = runTest {
        val backup = backup(paired = true)
        val labels = com.chencohensolution.transcriptor.notebook.LabelStore(fs, root)
        labels.create("Psychology", 1)
        assertEquals(false, backup.syncLabels(labels, 2))
        assertTrue("Psychology" in server.appData["labels"]!!)
        // Another phone adds one: it comes here.
        server.appData["labels"] = server.appData["labels"]!!.replace("]", ", {\"id\": \"x\", \"name\": \"Family\", \"color\": 3}]")
        assertEquals(true, backup.syncLabels(labels, 3))
        assertEquals(listOf("Psychology", "Family"), labels.all().map { it.name })
    }

    @Test
    fun freeUpRemovesAudioOnlyOnceTheServerHoldsItAndOpeningBringsItBack() = runTest {
        moment("a")
        moment("b")
        val backup = backup(paired = true)
        backup.backUpFinished(busy = setOf("b"))
        assertEquals(RunResult.Done(sent = 1, refused = 0), backup.runUploads())
        val plan = backup.freeUpPlan(FreeUpRule.Everything, now = 1_800_000_000_000)
        assertEquals(listOf("a"), plan.ids)
        assertEquals("the sound of a".length.toLong(), plan.bytes)
        assertEquals(emptyList(), backup.freeUpPlan(FreeUpRule.KeepNewest(1L shl 30), now = 1_800_000_000_000).ids)

        assertEquals(FreeUpResult(1, 14), backup.freeUp(plan.ids))
        val a = store.load("a")!!
        assertEquals(false, store.hasAudio(a))
        assertEquals(backup.sha256Of("a"), a.serverSha256)
        assertTrue(store.hasAudio(store.load("b")!!), "not on the server: kept")

        assertTrue(backup.bringAudio("a"))
        assertEquals("the sound of a", fs.read(store.audioPath(a)) { readUtf8() })
    }

    @Test
    fun aCallsTitleIsWhomItWasWith() {
        assertEquals("אבא", titleOf("Call recording אבא_250206_142813.m4a"))
        assertEquals("0501234567", titleOf("הקלטת שיחה 0501234567_211115_200618.m4a"))
        assertEquals("Voice 012", titleOf("Voice 012.m4a"))
        assertEquals("אחות הדר פרפל", titleOf("Call \u200e\u202bאחות הדר פרפל\u202c\u200e_260601_181926.m4a"))
        fun remote(name: String, source: String?) = RemoteFile("0".repeat(64), 1, FileAbout(name, "audio/mp4", FileKind.AUDIO, source = source))
        assertEquals("call", sourceOf(remote("Call \u200eאמא_260515_114640.m4a", "recording")))
        assertEquals("call", sourceOf(remote("שיחה אבא_261028_122919.m4a", "other")))
        assertEquals("recording", sourceOf(remote("audio.aac", "recording")))
    }

    @Test
    fun theServerIsToldWhenAndWhatTheMomentIs() {
        val about = backup(paired = false).aboutOf(moment("a"))
        assertEquals("Class a.flac", about.fileName)
        assertEquals("audio/flac", about.mimeType)
        assertEquals("2025-11-16T12:05:09+02:00", about.takenAt)
        assertEquals("recording", about.source)
    }

    @Test
    fun rfc3339WithTheOffset() {
        assertEquals("1970-01-01T00:00:00+00:00", rfc3339(0, 0))
        assertEquals("1969-12-31T19:30:00-04:30", rfc3339(0, -16_200_000))
        assertEquals("2024-02-29T23:59:59+00:00", rfc3339(1_709_251_199_000, 0))
    }
}

/** Keys that are always there: the pairing in the test is already made. */
private object PairedKeys : DeviceKeys {
    override fun generate(alias: String): DeviceKey = error("not in tests")
    override fun saveCertificate(alias: String, certificateDer: ByteArray) = Unit
    override fun delete(alias: String) = Unit
    override fun has(alias: String) = true
}

/** A storage in memory. */
private class MemoryStorage : ShedBackend {
    val files = mutableMapOf<String, ByteArray>()
    val abouts = mutableMapOf<String, FileAbout>()
    private val partial = mutableMapOf<String, ByteArray>()
    override val capabilities = Capabilities(changeList = true)

    override suspend fun startUpload(sha256: String, size: Long, about: FileAbout): UploadStart {
        abouts[sha256] = about
        return if (sha256 in files) UploadStart.Exists else UploadStart.Ready(partial[sha256]?.size?.toLong() ?: 0, 4)
    }

    override suspend fun sendPiece(sha256: String, offset: Long, bytes: ByteArray, total: Long): PieceAnswer {
        val have = partial[sha256] ?: ByteArray(0)
        if (offset != have.size.toLong()) return PieceAnswer.WrongOffset(have.size.toLong())
        val now = have + bytes
        if (now.size.toLong() < total) {
            partial[sha256] = now
            return PieceAnswer.Accepted(now.size.toLong(), complete = false)
        }
        partial.remove(sha256)
        files[sha256] = now
        return PieceAnswer.Accepted(total, complete = true)
    }

    override suspend fun size(sha256: String): Long? = files[sha256]?.size?.toLong()
    override suspend fun read(sha256: String, from: Long, length: Int): ByteArray =
        files[sha256]?.let { it.copyOfRange(from.toInt(), minOf(it.size, (from + length).toInt())) } ?: throw BackendException("gone")
    /** What the change list answers: everything listed, every time. */
    val listed = mutableListOf<RemoteFile>()
    val gone = mutableListOf<String>()
    override suspend fun changes(cursor: String?, kinds: Set<FileKind>) = ChangePage(listed.toList(), gone.toList(), "c", false)
    override suspend fun holds(sha256: String) = sha256 in files
    override suspend fun page(kind: FileKind, before: String?, after: String?, limit: Int) = pageOf(listed.toList(), before, after, limit)
    var appData = mutableMapOf<String, String>()
    override suspend fun appData(name: String) = AppData(appData[name], appData[name]?.hashCode()?.toString())
    override suspend fun setAppData(name: String, content: String?, baseVersion: String?): AppDataWrite {
        if (appData[name]?.hashCode()?.toString() != baseVersion) return AppDataWrite.Conflict(appData(name))
        if (content == null) appData.remove(name) else appData[name] = content
        return AppDataWrite.Saved(appData(name))
    }
}

/** A page of [files] (newest first) as a storage answers one: the cursor is a file's place in the list. */
private fun pageOf(files: List<RemoteFile>, before: String?, after: String?, limit: Int): FilePage {
    val from = when {
        before != null -> files.indexOfFirst { it.sha256 == before } + 1
        after != null -> maxOf(0, files.indexOfFirst { it.sha256 == after } - limit)
        else -> 0
    }
    val to = when {
        after != null -> files.indexOfFirst { it.sha256 == after }
        else -> minOf(files.size, from + limit)
    }
    val page = files.subList(from, maxOf(from, to))
    return FilePage(page, older = page.lastOrNull()?.sha256?.takeIf { to < files.size }, newer = page.firstOrNull()?.sha256?.takeIf { from > 0 })
}
