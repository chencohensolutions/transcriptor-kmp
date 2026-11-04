package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Place
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.notebook.adoptLibrary
import com.chencohensolution.transcriptor.notebook.archiveFinished
import com.chencohensolution.transcriptor.speech.Voice
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.notebook.RecordingNow
import com.chencohensolution.transcriptor.notebook.NotebookStore
import com.chencohensolution.transcriptor.notebook.NotebooksController
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.TRANSCRIPT
import com.chencohensolution.transcriptor.notebook.UNFILED
import com.chencohensolution.transcriptor.notebook.guessNotebook
import com.chencohensolution.transcriptor.notebook.notebooksState
import com.chencohensolution.transcriptor.notebook.transcriptMarkdown
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebooksTest {
    private val fs = FakeFileSystem()
    private val root = "/files".toPath()
    private val israel = { _: Long -> 2 * HOUR }
    private val lectures = LectureStore(fs, root, israel)
    private val notebooks = NotebookStore(fs, root)

    private fun lecture(id: String, notebook: String, at: Long, language: String = "he") =
        Lecture(id, notebook, "Lecture $id", language, at, 47 * MIN, emptyList(), emptyList())

    private fun summary(id: String, at: Long) = LectureSummary(id, id, at, 90 * MIN, 0, false)

    @Test
    fun notebooksAreKeptInAVersionedFileEachWithItsOwnColor() {
        val psych = notebooks.create(" מבוא לפסיכולוגיה ", "he", 1)
        val stats = notebooks.create("סטטיסטיקה", "he", 2)
        assertEquals(listOf(psych, stats), notebooks.all())
        assertEquals("מבוא לפסיכולוגיה", psych.title)
        assertEquals(listOf(0, 1), notebooks.all().map { it.color })
        assertTrue(fs.read(root / "notebooks.json") { readUtf8() }.contains("\"v\": 1"))
        assertFalse(fs.exists(root / "notebooks.json.tmp"))
    }

    @Test
    fun theOldDefaultNotebooksLecturesBecomeUnfiledOnce() {
        val old = lecture("lecture-1", "default", MONDAY_10)
        lectures.save(old)
        fs.write(lectures.audioPath(old)) { writeUtf8("audio") }
        fs.write(root / "notebooks" / "default" / "voices.json") { writeUtf8("[]") }

        notebooks.migrate(lectures)

        assertFalse(fs.exists(root / "notebooks" / "default"))
        val moved = assertNotNull(lectures.load(UNFILED, "lecture-1"))
        assertEquals(UNFILED, moved.notebookId)
        assertEquals("audio", fs.read(lectures.audioPath(moved)) { readUtf8() })
        assertEquals(emptyList(), notebooks.all())

        // Not again: a later "default" folder is left alone.
        lectures.save(lecture("lecture-2", "default", MONDAY_10))
        notebooks.migrate(lectures)
        assertNotNull(lectures.load("default", "lecture-2"))
    }

    @Test
    fun aLectureMovesWithItsAudioTranscriptAndVersions() {
        val l = lecture("lecture-1", UNFILED, MONDAY_10)
        lectures.save(l)
        fs.write(lectures.audioPath(l)) { writeUtf8("audio") }
        fs.createDirectories(lectures.folder(UNFILED, l.id) / "versions")
        fs.write(lectures.folder(UNFILED, l.id) / "versions" / "v1.json") { writeUtf8("{}") }

        val moved = lectures.move(l, "psych")

        assertEquals("psych", moved.notebookId)
        assertFalse(fs.exists(lectures.folder(UNFILED, l.id)))
        assertEquals(moved, lectures.load("psych", l.id))
        assertEquals("audio", fs.read(lectures.audioPath(moved)) { readUtf8() })
        assertTrue(fs.exists(lectures.folder("psych", l.id) / TRANSCRIPT))
        assertTrue(fs.exists(lectures.folder("psych", l.id) / "versions" / "v1.json"))
    }

    @Test
    fun theTranscriptIsWrittenBesideTheAudioOnEverySave() {
        val l = lecture("lecture-1", "psych", MONDAY_10)
        lectures.save(l)
        assertTrue(fs.read(lectures.folder("psych", l.id) / TRANSCRIPT) { readUtf8() }.startsWith("# Lecture lecture-1"))
        lectures.save(l.copy(title = "זיכרון עבודה"))
        assertTrue(fs.read(lectures.folder("psych", l.id) / TRANSCRIPT) { readUtf8() }.startsWith("# זיכרון עבודה"))
        assertEquals(AUDIO_WAV, l.audioFile)
    }

    @Test
    fun theTranscriptReadsAsTurnsWithTheNotesInPlace() {
        val l = lecture("lecture-7", "psych", MONDAY_10).copy(
            title = "זיכרון עבודה",
            durationMillis = 3 * MIN + 15_000,
            speakers = listOf(Speaker("s1", "המרצה"), Speaker("s2", "")),
            segments = listOf(
                Segment("a", 2_000, 14_000, "בוקר טוב לכולם.", "m", speakerId = "s1"),
                Segment("b", 15_000, 31_000, "היום: *זיכרון* עבודה.", "m", speakerId = "s1"),
                Segment("c", 97_000, 104_000, "זה נכנס למבחן?", "m", speakerId = "s2"),
                Segment("d", 105_000, 118_000, "הכול נכנס.", "m", speakerId = "s1"),
            ),
            notes = listOf(Note("n2", 100_000, "לשאול על המבחן"), Note("n1", 20_000, "פסיבי ↔ אקטיבי")),
        )
        val expected = """
            # זיכרון עבודה

            2026-11-02 10:00 · 3:15

            **המרצה** · 0:02
            בוקר טוב לכולם. היום: \*זיכרון\* עבודה.

            > 📝 0:20 — פסיבי ↔ אקטיבי

            **דובר 2** · 1:37
            זה נכנס למבחן?

            > 📝 1:40 — לשאול על המבחן

            **המרצה** · 1:45
            הכול נכנס.
        """.trimIndent() + "\n"
        assertEquals(expected, transcriptMarkdown(l, 2 * HOUR))
    }

    @Test
    fun theNotebookOfTheUsualHourIsGuessed() {
        val psych = Notebook("psych", "Psych", "he", (1..3).map { summary("p$it", MONDAY_10 - it * WEEK) })
        val stats = Notebook("stats", "Stats", "he", listOf(summary("s1", MONDAY_10 - WEEK - 2 * DAY)))
        val guess = assertNotNull(guessNotebook(listOf(psych, stats), MONDAY_10 - 10 * MIN, israel))
        assertEquals("psych", guess.notebookId)
        assertEquals(3, guess.times)
        assertEquals(MONDAY_10 - WEEK, guess.usualStartMillis)
        // 45 minutes late still counts; two hours late doesn't; nor another weekday.
        assertEquals("psych", guessNotebook(listOf(psych), MONDAY_10 + 45 * MIN, israel)?.notebookId)
        assertNull(guessNotebook(listOf(psych), MONDAY_10 + 2 * HOUR, israel))
        assertNull(guessNotebook(listOf(psych), MONDAY_10 + DAY, israel))
    }

    @Test
    fun aOneOffIsNotAHabitAndTodaysOwnLectureDoesNotCount() {
        val once = Notebook("once", "Once", "he", listOf(summary("o1", MONDAY_10 - WEEK)))
        assertNull(guessNotebook(listOf(once), MONDAY_10, israel))
        val today = Notebook("t", "T", "he", listOf(summary("t1", MONDAY_10 - HOUR), summary("t2", MONDAY_10 - WEEK)))
        assertNull(guessNotebook(listOf(today), MONDAY_10, israel))
    }

    @Test
    fun aTieGoesToTheNotebookUsedLast() {
        val a = Notebook("a", "A", "he", listOf(summary("a1", MONDAY_10 - 3 * WEEK), summary("a2", MONDAY_10 - 2 * WEEK)))
        val b = Notebook("b", "B", "he", listOf(summary("b1", MONDAY_10 - 2 * WEEK), summary("b2", MONDAY_10 - WEEK)))
        assertEquals("b", guessNotebook(listOf(a, b), MONDAY_10, israel)?.notebookId)
    }

    @Test
    fun theHourIsThePhonesAtEachDate() {
        // Summer lectures at 10:00 local (UTC+3, 7:00 UTC); now winter, 10:50 local (UTC+2, 8:50 UTC). The phone's
        // offset at each date makes it the same class; one offset for all would put them 110 minutes apart.
        val winter = MONDAY_10 + 50 * MIN
        val summerOffset = { at: Long -> if (at < winter - 4 * WEEK) 3 * HOUR else 2 * HOUR }
        val summer = (5..6).map { winter - 50 * MIN - it * WEEK - HOUR }
        val n = Notebook("n", "N", "he", summer.mapIndexed { i, at -> summary("s$i", at) })
        assertEquals("n", guessNotebook(listOf(n), winter, summerOffset)?.notebookId)
        assertNull(guessNotebook(listOf(n), winter, { 0 }))
    }

    @Test
    fun theControllerListsNotebooksUnfiledLecturesAndFilesThem() {
        val psych = notebooks.create("Psych", "he", 1)
        lectures.save(lecture("p1", psych.id, MONDAY_10 - 2 * WEEK))
        lectures.save(lecture("p2", psych.id, MONDAY_10 - WEEK))
        lectures.save(lecture("u1", UNFILED, MONDAY_10 + 5 * MIN, language = "en"))
        lectures.save(lecture("u0", UNFILED, MONDAY_10 - 3 * DAY))
        val controller = NotebooksController(notebooks, lectures) { 100 }
        controller.reload()

        val library = controller.library.value
        assertEquals(listOf("p2", "p1"), library.notebooks.single().lectures.map { it.id })
        assertEquals(listOf("u1", "u0"), library.unfiled.map { it.id })
        assertEquals("en", library.unfiledLanguage)

        val state = notebooksState(library, MONDAY_10 + WEEK - 10 * MIN, israel)
        assertEquals(psych.id, state.guess?.notebookId)
        assertEquals(psych.id, state.suggested["u1"]?.notebookId)
        assertNull(state.suggested["u0"])

        // The lecture recording now is the banner, not in the lists.
        val recording = RecordingNow(null, "Lecture u1", 5_000, lectureId = "u1")
        assertEquals(listOf("u0"), notebooksState(library, MONDAY_10, israel, recording).unfiled.map { it.id })

        controller.assign("u1", psych.id)
        assertEquals(listOf("u1", "p2", "p1"), controller.library.value.notebooks.single().lectures.map { it.id })
        assertNull(controller.assign("u1", psych.id))

        val english = assertNotNull(controller.assignToNew("u0", "English", "en"))
        assertEquals(listOf("Psych", "English"), controller.library.value.notebooks.map { it.title })
        assertEquals(english.notebookId, controller.library.value.notebooks.last().id)
        assertEquals(emptyList(), controller.library.value.unfiled)
    }

    @Test
    fun withNoLecturesTheUnfiledLanguageIsHebrew() {
        val controller = NotebooksController(notebooks, lectures) { 0 }
        controller.reload()
        assertEquals("he", controller.library.value.unfiledLanguage)
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY

        /** Monday 2 November 2026, 10:00 in Israel (UTC+2). */
        const val MONDAY_10 = 1_793_606_400_000L
    }
}

/** A file system whose writes, once [dropping], lose their bytes: a copy that arrives short. */
private class Lossy(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    var dropping = false
    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val sink = super.sink(file, mustCreate)
        return if (!dropping) sink else object : Sink by sink {
            override fun write(source: Buffer, byteCount: Long) = source.skip(byteCount)
        }
    }
}

class LibraryFolderTest {
    private val phone = FakeFileSystem()
    private val folder = Lossy(FakeFileSystem())
    private val lectures = LectureStore(phone, "/files".toPath())
    private val notebooks = NotebookStore { lectures.library ?: lectures.local }
    private val voices = VoiceStore { lectures.library ?: lectures.local }
    private val library = Place(folder, "/library".toPath())

    private fun recorded(id: String, notebook: String): Lecture {
        val l = Lecture(id, notebook, "Lecture $id", "he", 1_000, 60_000, emptyList(), emptyList())
        lectures.save(l)
        phone.write(lectures.audioPath(l)) { writeUtf8("audio of $id") }
        return l
    }

    @Test
    fun choosingTheFolderMovesNotebooksVoicesAndFinishedLecturesThere() {
        val psych = notebooks.create("Psych", "he", 1)
        voices.save(psych.id, Voice("המרצה", listOf(0.1f, 0.2f)))
        recorded("a", psych.id)
        recorded("b", UNFILED)
        recorded("open", psych.id)

        adoptLibrary(lectures, notebooks, library, busy = setOf("open"))

        assertEquals(library, lectures.library)
        assertEquals(listOf("Psych"), notebooks.all().map { it.title })
        assertTrue(folder.exists("/library/notebooks.json".toPath()))
        assertFalse(phone.exists("/files/notebooks.json".toPath()))
        assertEquals("המרצה", voices.load(psych.id).single().name)
        assertEquals("audio of a", folder.read("/library/notebooks/${psych.id}/a/audio.wav".toPath()) { readUtf8() })
        assertTrue(folder.exists("/library/notebooks/${psych.id}/a/$TRANSCRIPT".toPath()))
        assertFalse(phone.exists(lectures.local.folder(psych.id, "a")))
        assertFalse(lectures.isLocal(assertNotNull(lectures.load(UNFILED, "b"))))
        // The open lecture stays here, and is still found.
        assertTrue(lectures.isLocal(assertNotNull(lectures.load(psych.id, "open"))))
        assertEquals(setOf("a", "b", "open"), lectures.all().map { it.id }.toSet())

        assertEquals(1, archiveFinished(lectures, busy = emptySet()))
        assertFalse(phone.exists(lectures.local.folder(psych.id, "open")))
    }

    @Test
    fun newLecturesRecordHereAndMoveWhenFinishedNotWhileBusyOrWaitingForFlac() {
        adoptLibrary(lectures, notebooks, library)
        val recording = recorded("now", UNFILED)
        assertTrue(lectures.isLocal(recording))
        lectures.save(recorded("wav", UNFILED).copy(pendingFlac = true))
        assertEquals(0, archiveFinished(lectures, busy = setOf("now")))
        assertEquals(1, archiveFinished(lectures, busy = emptySet()))
        assertFalse(lectures.isLocal(assertNotNull(lectures.load(UNFILED, "now"))))
        assertTrue(lectures.isLocal(assertNotNull(lectures.load(UNFILED, "wav"))))
    }

    @Test
    fun aLectureInTheLibraryIsEditedAndFiledThere() {
        val psych = notebooks.create("Psych", "he", 1)
        adoptLibrary(lectures, notebooks, library)
        recorded("a", UNFILED)
        archiveFinished(lectures, emptySet())
        val a = assertNotNull(lectures.load(UNFILED, "a"))
        lectures.save(a.copy(title = "זיכרון"))
        val moved = lectures.move(assertNotNull(lectures.load(UNFILED, "a")), psych.id)
        assertEquals("זיכרון", lectures.load(psych.id, "a")?.title)
        assertEquals("/library/notebooks/${psych.id}/a/audio.wav".toPath(), lectures.audioPath(moved))
        assertFalse(phone.exists(lectures.local.folder(psych.id, "a")))
    }

    @Test
    fun afterAReinstallTheLibrarysNotebooksAndLecturesComeBack() {
        val old = LectureStore(folder, "/old".toPath())
        // What the library held before: written as a previous install left it.
        val before = NotebookStore(folder, "/library".toPath())
        val psych = before.create("Psych", "he", 1)
        LectureStore(folder, "/library".toPath()).save(Lecture("kept", psych.id, "Kept", "he", 1, 1, emptyList(), emptyList()))
        // A new install: a notebook made before the folder was picked again.
        notebooks.create("Stats", "he", 2)

        adoptLibrary(lectures, notebooks, library)

        assertEquals(listOf("Psych", "Stats"), notebooks.all().map { it.title })
        assertEquals(listOf("kept"), lectures.all().map { it.id })
        assertEquals(emptyList(), old.all())
    }

    @Test
    fun changingTheFolderMovesEverythingIncludingTheOpenLecture() {
        val psych = notebooks.create("Psych", "he", 1)
        voices.save(psych.id, Voice("המרצה", listOf(0.5f)))
        recorded("a", psych.id)
        adoptLibrary(lectures, notebooks, library)
        val next = Place(folder, "/next".toPath())

        adoptLibrary(lectures, notebooks, next, busy = setOf("a"))

        assertEquals(next, lectures.library)
        assertEquals(listOf("Psych"), notebooks.all().map { it.title })
        assertEquals("המרצה", voices.load(psych.id).single().name)
        assertEquals("/next/notebooks/${psych.id}/a/audio.wav".toPath(), lectures.audioPath(assertNotNull(lectures.load(psych.id, "a"))))
        assertFalse(folder.exists(library.folder(psych.id, "a")))
        assertFalse(folder.exists("/library/notebooks.json".toPath()))
    }

    @Test
    fun aFailedCopyLeavesTheLectureHere() {
        adoptLibrary(lectures, notebooks, library)
        val a = recorded("a", UNFILED)
        folder.dropping = true
        assertEquals(0, archiveFinished(lectures, emptySet()))
        folder.dropping = false
        assertFalse(folder.exists(library.folder(UNFILED, "a")))
        assertTrue(lectures.isLocal(assertNotNull(lectures.load(UNFILED, "a"))))
        assertEquals("audio of a", phone.read(lectures.audioPath(a)) { readUtf8() })
    }

    @Test
    fun anUnreachableLibraryListsOnlyTheLecturesHere() {
        adoptLibrary(lectures, notebooks, library)
        recorded("a", UNFILED)
        archiveFinished(lectures, emptySet())
        recorded("b", UNFILED)
        lectures.library = Place(folder, "/gone".toPath())
        assertEquals(listOf("b"), lectures.all().map { it.id })
    }
}
