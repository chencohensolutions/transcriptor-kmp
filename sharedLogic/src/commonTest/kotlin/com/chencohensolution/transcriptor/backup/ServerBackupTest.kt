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
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Place
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    private val partial = mutableMapOf<String, ByteArray>()
    override val capabilities = Capabilities()

    override suspend fun startUpload(sha256: String, size: Long, about: FileAbout): UploadStart =
        if (sha256 in files) UploadStart.Exists else UploadStart.Ready(partial[sha256]?.size?.toLong() ?: 0, 4)

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
    override suspend fun changes(cursor: String?) = ChangePage(emptyList<RemoteFile>(), emptyList(), "c", false)
    override suspend fun holds(sha256: String) = sha256 in files
}
