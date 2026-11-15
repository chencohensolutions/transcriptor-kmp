package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.Languages
import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.Label
import com.chencohensolution.transcriptor.notebook.LabelStore
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.NOTEBOOKS_FILE
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.NotebooksController
import com.chencohensolution.transcriptor.notebook.Place
import com.chencohensolution.transcriptor.notebook.RecordingNow
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.TRANSCRIPT
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.notebook.adoptLibrary
import com.chencohensolution.transcriptor.notebook.archiveFinished
import com.chencohensolution.transcriptor.notebook.guessLabels
import com.chencohensolution.transcriptor.notebook.labelNames
import com.chencohensolution.transcriptor.notebook.migrateToLabels
import com.chencohensolution.transcriptor.notebook.notebooksState
import com.chencohensolution.transcriptor.notebook.transcriptMarkdown
import com.chencohensolution.transcriptor.settings.SettingsStore
import com.chencohensolution.transcriptor.speech.Voice
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
    private val labels = LabelStore(fs, root)

    private fun lecture(id: String, at: Long, language: String = "he", labels: List<String> = emptyList()) =
        Lecture(id, "Lecture $id", language, at, 47 * MIN, emptyList(), emptyList(), labels = labels)

    private fun summary(id: String, at: Long, vararg labels: String) = LectureSummary(id, id, at, 90 * MIN, 0, false, "he", labels.toList())

    /** A lecture as the app kept it before labels: `notebooks/<notebook>/<id>/`, with its audio. */
    private fun before(notebook: String, l: Lecture) {
        lectures.save(l)
        fs.write(lectures.audioPath(l)) { writeUtf8("audio of ${l.id}") }
        fs.createDirectories(root / "notebooks" / notebook)
        fs.atomicMove(root / "moments" / l.id, root / "notebooks" / notebook / l.id)
    }

    @Test
    fun labelsAreKeptInAVersionedFileEachWithItsOwnColor() {
        val psych = labels.create(" פסיכולוגיה ", 1)
        val exam = labels.create("Exam", 2)
        assertEquals(listOf(psych, exam), labels.all())
        assertEquals("פסיכולוגיה", psych.name)
        assertEquals(listOf(0, 1), labels.all().map { it.color })
        // The same name (any case) is the same label.
        assertEquals(exam, labels.create("exam", 3))
        assertTrue(fs.read(root / "labels.json") { readUtf8() }.contains("\"v\": 1"))
        assertFalse(fs.exists(root / "labels.json.tmp"))
        labels.rename(exam.id, "מבחן")
        assertEquals(listOf("פסיכולוגיה", "מבחן"), labels.all().map { it.name })
        labels.delete(psych.id)
        assertEquals(listOf(exam.id), labels.all().map { it.id })
        // A new label takes the first free color.
        assertEquals(0, labels.create("Stats", 4).color)
    }

    @Test
    fun aMomentIsItsOwnFolderWithItsLabelsInItsFile() {
        val l = lecture("lecture-1", MONDAY_10, labels = listOf("psych", "exam"))
        lectures.save(l)
        assertEquals(root / "moments" / "lecture-1", lectures.folder(l.id))
        assertEquals(l, lectures.load("lecture-1"))
        assertTrue(fs.read(lectures.folder(l.id) / "lecture.json") { readUtf8() }.contains("\"labels\""))
        val relabeled = lectures.setLabels(l, listOf("exam"))
        assertEquals(listOf("exam"), lectures.load(l.id)?.labels)
        assertEquals(relabeled, lectures.load(l.id))
    }

    @Test
    fun notebooksBecomeLabelsAndEveryLectureKeepsItsFilesAndVoices() {
        fs.createDirectories(root)
        fs.write(root / NOTEBOOKS_FILE) {
            writeUtf8("""{"v": 1, "notebooks": [{"id": "psych", "title": "פסיכולוגיה", "language": "he", "color": 0, "createdAt": 1},
                {"id": "stats", "title": "Stats", "language": "he", "color": 4, "createdAt": 2}]}""")
        }
        before("psych", lecture("p1", MONDAY_10).copy(notes = listOf(Note("n", 1_000, "הערה"))))
        before("stats", lecture("s1", MONDAY_10 + DAY))
        before("unfiled", lecture("u1", MONDAY_10 + 2 * DAY))
        before("default", lecture("d1", MONDAY_10 - WEEK))
        fs.createDirectories(root / "notebooks" / "psych" / "p1" / "versions")
        fs.write(root / "notebooks" / "psych" / "p1" / "versions" / "v1.json") { writeUtf8("{}") }
        VoiceStore(fs, root / "notebooks" / "psych").save(Voice("Lecturer", listOf(1f), "cam"))
        VoiceStore(fs, root / "notebooks" / "psych").save(Voice("Dana", listOf(2f), "cam"))
        VoiceStore(fs, root / "notebooks" / "stats").save(Voice("Lecturer", listOf(3f), "cam"))

        migrateToLabels(Place(fs, root), now = 10, utcOffset = israel)

        assertEquals(listOf(Label("psych", "פסיכולוגיה", 0), Label("stats", "Stats", 4)), labels.all())
        assertEquals(listOf("psych"), lectures.load("p1")?.labels)
        assertEquals(listOf("הערה"), lectures.load("p1")?.notes?.map { it.text })
        assertEquals(listOf("stats"), lectures.load("s1")?.labels)
        assertEquals(emptyList(), lectures.load("u1")?.labels)
        assertEquals(emptyList(), lectures.load("d1")?.labels)
        assertEquals("audio of p1", fs.read(root / "moments" / "p1" / AUDIO_WAV) { readUtf8() })
        assertTrue(fs.exists(root / "moments" / "p1" / "versions" / "v1.json"))
        assertTrue(fs.read(root / "moments" / "p1" / TRANSCRIPT) { readUtf8() }.contains("2026-11-02 10:00"))
        assertEquals(
            setOf("Lecturer · פסיכולוגיה", "Dana", "Lecturer · Stats"),
            VoiceStore(fs, root) { "cam" }.load().map { it.name }.toSet(),
        )
        assertFalse(fs.exists(root / "notebooks"))
        assertFalse(fs.exists(root / NOTEBOOKS_FILE))

        // Once: nothing changes the next time.
        labels.rename("psych", "Psych")
        migrateToLabels(Place(fs, root), now = 20)
        assertEquals("Psych", labels.all().first().name)
    }

    @Test
    fun aMigrationCutOffGoesOnAndNeverOverwritesAMoment() {
        fs.createDirectories(root)
        fs.createDirectories(root)
        fs.write(root / NOTEBOOKS_FILE) { writeUtf8("""{"v": 1, "notebooks": [{"id": "psych", "title": "P", "language": "he", "color": 0, "createdAt": 1}]}""") }
        before("psych", lecture("a", MONDAY_10))
        before("psych", lecture("b", MONDAY_10))
        // "b" is in moments/ already (as if moved before a crash) and a copy is still in the notebook.
        lectures.save(lecture("b", MONDAY_10).copy(title = "moved"))

        migrateToLabels(Place(fs, root), now = 1)

        assertEquals(listOf("psych"), lectures.load("a")?.labels)
        assertEquals("moved", lectures.load("b")?.title)
        // What couldn't move stays, with the notebooks' file, for the next start.
        assertTrue(fs.exists(root / "notebooks" / "psych" / "b" / "lecture.json"))
        assertTrue(fs.exists(root / NOTEBOOKS_FILE))
    }

    @Test
    fun theTranscriptIsWrittenBesideTheAudioOnEverySave() {
        val l = lecture("lecture-1", MONDAY_10)
        lectures.save(l)
        assertTrue(fs.read(lectures.folder(l.id) / TRANSCRIPT) { readUtf8() }.startsWith("# Lecture lecture-1"))
        lectures.save(l.copy(title = "זיכרון עבודה"))
        assertTrue(fs.read(lectures.folder(l.id) / TRANSCRIPT) { readUtf8() }.startsWith("# זיכרון עבודה"))
        assertEquals(AUDIO_WAV, l.audioFile)
    }

    @Test
    fun theTranscriptReadsAsTurnsWithTheNotesInPlace() {
        val l = lecture("lecture-7", MONDAY_10).copy(
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
    fun theLabelsOfTheUsualHourAreGuessed() {
        val moments = (1..3).map { summary("p$it", MONDAY_10 - it * WEEK, "psych") } + summary("s1", MONDAY_10 - WEEK - 2 * DAY, "stats")
        val guess = assertNotNull(guessLabels(moments, MONDAY_10 - 10 * MIN, israel))
        assertEquals(listOf("psych"), guess.labels)
        assertEquals(MONDAY_10 - WEEK, guess.usualStartMillis)
        // 45 minutes late still counts; two hours late doesn't; nor another weekday.
        assertEquals(listOf("psych"), guessLabels(moments, MONDAY_10 + 45 * MIN, israel)?.labels)
        assertNull(guessLabels(moments, MONDAY_10 + 2 * HOUR, israel))
        assertNull(guessLabels(moments, MONDAY_10 + DAY, israel))
    }

    @Test
    fun aClassWithTwoLabelsGuessesBothButAnOccasionalLabelIsNotItsHabit() {
        val both = (1..3).map { summary("p$it", MONDAY_10 - it * WEEK, "psych", "dana") }
        assertEquals(listOf("psych", "dana"), guessLabels(both, MONDAY_10, israel)?.labels?.sorted()?.reversed())
        // An exam on two of five Monday classes.
        val exams = (1..5).map { i -> summary("p$i", MONDAY_10 - i * WEEK, *(if (i <= 2) arrayOf("psych", "exam") else arrayOf("psych"))) }
        assertEquals(listOf("psych"), guessLabels(exams, MONDAY_10, israel)?.labels)
        // Moments without labels are no habit.
        assertNull(guessLabels((1..3).map { summary("u$it", MONDAY_10 - it * WEEK) }, MONDAY_10, israel))
    }

    @Test
    fun aOneOffIsNotAHabitAndTodaysOwnMomentDoesNotCount() {
        assertNull(guessLabels(listOf(summary("o1", MONDAY_10 - WEEK, "once")), MONDAY_10, israel))
        assertNull(guessLabels(listOf(summary("t1", MONDAY_10 - HOUR, "t"), summary("t2", MONDAY_10 - WEEK, "t")), MONDAY_10, israel))
    }

    @Test
    fun aTieGoesToTheLabelUsedLast() {
        val moments = listOf(
            summary("a1", MONDAY_10 - 3 * WEEK, "a"), summary("a2", MONDAY_10 - 2 * WEEK, "a"),
            summary("b1", MONDAY_10 - 2 * WEEK - 10 * MIN, "b"), summary("b2", MONDAY_10 - WEEK, "b"),
        )
        assertEquals("b", guessLabels(moments, MONDAY_10, israel)?.labels?.first())
    }

    @Test
    fun theHourIsThePhonesAtEachDate() {
        // Summer moments at 10:00 local (UTC+3, 7:00 UTC); now winter, 10:50 local (UTC+2, 8:50 UTC). The phone's
        // offset at each date makes it the same class; one offset for all would put them 110 minutes apart.
        val winter = MONDAY_10 + 50 * MIN
        val summerOffset = { at: Long -> if (at < winter - 4 * WEEK) 3 * HOUR else 2 * HOUR }
        val summer = (5..6).map { summary("s$it", winter - 50 * MIN - it * WEEK - HOUR, "n") }
        assertEquals(listOf("n"), guessLabels(summer, winter, summerOffset)?.labels)
        assertNull(guessLabels(summer, winter, { 0 }))
    }

    @Test
    fun theControllerListsMomentsSuggestsLabelsAndSetsThem() {
        val psych = labels.create("Psych", 1)
        val exam = labels.create("Exam", 2)
        lectures.save(lecture("p1", MONDAY_10 - 2 * WEEK, labels = listOf(psych.id)))
        lectures.save(lecture("p2", MONDAY_10 - WEEK, labels = listOf(psych.id, "gone")))
        lectures.save(lecture("u1", MONDAY_10 + 5 * MIN, language = "en"))
        lectures.save(lecture("u0", MONDAY_10 - 3 * DAY, language = "en"))
        val controller = NotebooksController(labels, lectures, { 100 })
        controller.reload()

        val library = controller.library.value
        assertEquals(listOf("u1", "u0", "p2", "p1"), library.moments.map { it.id })
        // A label that no longer exists isn't shown.
        assertEquals(listOf(psych.id), library.moments.first { it.id == "p2" }.labels)
        assertEquals("en", library.lastLanguage)

        val state = notebooksState(library, MONDAY_10 + WEEK - 10 * MIN, israel)
        assertEquals(listOf(psych.id), state.guess?.labels)
        // The guessed label's moments are in Hebrew: the next one is too, though the last was in English.
        assertEquals("he", state.language)
        assertEquals("en", notebooksState(library, MONDAY_10 + WEEK + DAY, israel).language)
        assertEquals(psych.id, state.suggested["u1"])
        assertNull(state.suggested["u0"])

        // The moment recording now is the banner, not in the list.
        val recording = RecordingNow(emptyList(), "Lecture u1", 5_000, lectureId = "u1")
        assertEquals(listOf("u0", "p2", "p1"), notebooksState(library, MONDAY_10, israel, recording).moments.map { it.id })

        assertEquals(listOf("u1", "u0"), controller.setLabels(listOf("u1", "u0", "p1"), add = setOf(psych.id, exam.id), remove = emptySet(), busy = { it == "p1" }))
        assertEquals(listOf(psych.id, exam.id), lectures.load("u1")?.labels)
        assertEquals(listOf(psych.id), lectures.load("p1")?.labels)
        controller.setLabels(listOf("u1"), add = emptySet(), remove = setOf(psych.id))
        assertEquals(listOf(exam.id), controller.library.value.moments.first { it.id == "u1" }.labels)
        assertEquals("Psych, Exam", labelNames(listOf(psych.id, exam.id, "gone"), controller.library.value.labels))

        val stats = controller.newLabel("Stats")
        assertEquals(listOf("Psych", "Exam", "Stats"), controller.library.value.labels.map { it.name })
        controller.renameLabel(stats.id, "Statistics")
        assertEquals("Statistics", controller.library.value.labels.last().name)

        // Deleting a label keeps its moments, without it; a busy one keeps the id (and shows nothing for it).
        controller.deleteLabel(exam.id, busy = { it == "u0" })
        assertEquals(emptyList(), lectures.load("u1")?.labels)
        assertEquals(listOf(psych.id, exam.id), lectures.load("u0")?.labels)
        assertEquals(listOf(psych.id), controller.library.value.moments.first { it.id == "u0" }.labels)
    }

    @Test
    fun withNoMomentsTheLanguageIsThePhones() {
        val controller = NotebooksController(labels, lectures, { 0 }, { Languages.default(listOf("iw")) })
        controller.reload()
        assertEquals("he", controller.library.value.lastLanguage)
        val english = NotebooksController(labels, lectures, { 0 })
        english.reload()
        assertEquals("en", english.library.value.lastLanguage)
    }

    @Test
    fun everyWhisperLanguageIsOfferedThePhonesFirst() {
        assertEquals("he", Languages.normalize("iw-IL"))
        assertEquals("id", Languages.normalize("in"))
        assertEquals("he", Languages.default(listOf("iw", "en")))
        assertEquals("fr", Languages.default(listOf("fr-CA")))
        assertEquals("en", Languages.default(listOf("xx")))
        assertTrue(Languages.all.size >= 99)
        val offered = Languages.ordered(listOf("fr", "iw"), used = listOf("de"))
        assertEquals(listOf("fr", "he", "de"), offered.take(3))
        assertEquals(offered.distinct(), offered)
        assertEquals(Languages.all.toSet(), offered.toSet())
    }

    @Test
    fun anUnnamedSpeakerIsNamedInTheLecturesLanguage() {
        val l = lecture("x", MONDAY_10, language = "de").copy(
            speakers = listOf(Speaker("s1", "")),
            segments = listOf(Segment("a", 0, 1_000, "Guten Morgen.", "m", speakerId = "s1")),
        )
        assertTrue(transcriptMarkdown(l).contains("**Sprecher 1** · 0:00"))
        assertTrue(transcriptMarkdown(l.copy(language = "sw")).contains("**Speaker 1** · 0:00"))
    }

    @Test
    fun deletingMomentsRemovesTheirFoldersWhereverTheyAreAndSkipsBusyOnes() {
        lectures.save(lecture("a", 10 * DAY))
        lectures.save(lecture("b", 11 * DAY))
        lectures.save(lecture("c", 12 * DAY))
        val library = Place(fs, "/library".toPath())
        lectures.library = library
        lectures.archive(lectures.load("c")!!)
        val controller = NotebooksController(labels, lectures, { 20 * DAY })
        controller.reload()

        assertEquals(listOf("b", "c"), controller.delete(listOf("a", "b", "c", "nope"), busy = { it == "a" }))
        assertTrue(fs.exists(root / "moments" / "a" / "lecture.json"))
        assertNull(lectures.load("b"))
        assertFalse(fs.exists(library.folder("c")))
        assertEquals(listOf("a"), controller.library.value.moments.map { it.id })
        assertFalse(lectures.delete("c"))
    }

    @Test
    fun movesWaitingToBeImprovedKeptBeforeLabelsAreReadByLectureId() {
        fs.createDirectories(root)
        fs.write(root / "settings.json") { writeUtf8("""{"v": 1, "improvePending": ["psych/l1", "l2"]}""") }
        val settings = SettingsStore(fs, root)
        assertEquals(listOf("l1", "l2"), settings.improvePending)
        settings.removeImprovePending("l1")
        assertEquals(listOf("l2"), settings.improvePending)
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
    private val labels = LabelStore { lectures.library ?: lectures.local }
    private val voices = VoiceStore { lectures.library ?: lectures.local }
    private val library = Place(folder, "/library".toPath())

    private fun recorded(id: String, vararg labels: String): Lecture {
        val l = Lecture(id, "Lecture $id", "he", 1_000, 60_000, emptyList(), emptyList(), labels = labels.toList())
        lectures.save(l)
        phone.write(lectures.audioPath(l)) { writeUtf8("audio of $id") }
        return l
    }

    @Test
    fun choosingTheFolderMovesLabelsVoicesAndFinishedMomentsThere() {
        val psych = labels.create("Psych", 1)
        voices.save(Voice("המרצה", listOf(0.1f, 0.2f)))
        recorded("a", psych.id)
        recorded("b")
        recorded("open", psych.id)

        adoptLibrary(lectures, labels, library, busy = setOf("open"))

        assertEquals(library, lectures.library)
        assertEquals(listOf("Psych"), labels.all().map { it.name })
        assertTrue(folder.exists("/library/labels.json".toPath()))
        assertFalse(phone.exists("/files/labels.json".toPath()))
        assertEquals("המרצה", voices.load().single().name)
        assertFalse(phone.exists("/files/voices.json".toPath()))
        assertEquals("audio of a", folder.read("/library/moments/a/audio.wav".toPath()) { readUtf8() })
        assertTrue(folder.exists("/library/moments/a/$TRANSCRIPT".toPath()))
        assertEquals(listOf(psych.id), lectures.load("a")?.labels)
        assertFalse(phone.exists(lectures.local.folder("a")))
        assertFalse(lectures.isLocal(assertNotNull(lectures.load("b"))))
        // The open moment stays here, and is still found.
        assertTrue(lectures.isLocal(assertNotNull(lectures.load("open"))))
        assertEquals(setOf("a", "b", "open"), lectures.all().map { it.id }.toSet())

        assertEquals(1, archiveFinished(lectures, busy = emptySet()))
        assertFalse(phone.exists(lectures.local.folder("open")))
    }

    @Test
    fun newMomentsRecordHereAndMoveWhenFinishedNotWhileBusyOrWaitingForFlac() {
        adoptLibrary(lectures, labels, library)
        val recording = recorded("now")
        assertTrue(lectures.isLocal(recording))
        lectures.save(recorded("wav").copy(pendingFlac = true))
        assertEquals(0, archiveFinished(lectures, busy = setOf("now")))
        assertEquals(1, archiveFinished(lectures, busy = emptySet()))
        assertFalse(lectures.isLocal(assertNotNull(lectures.load("now"))))
        assertTrue(lectures.isLocal(assertNotNull(lectures.load("wav"))))
    }

    @Test
    fun aMomentInTheLibraryIsEditedAndLabeledThere() {
        val psych = labels.create("Psych", 1)
        adoptLibrary(lectures, labels, library)
        recorded("a")
        archiveFinished(lectures, emptySet())
        val a = assertNotNull(lectures.load("a"))
        lectures.save(a.copy(title = "זיכרון"))
        val labeled = lectures.setLabels(assertNotNull(lectures.load("a")), listOf(psych.id))
        assertEquals("זיכרון", lectures.load("a")?.title)
        assertEquals(listOf(psych.id), lectures.load("a")?.labels)
        assertEquals("/library/moments/a/audio.wav".toPath(), lectures.audioPath(labeled))
        assertFalse(phone.exists(lectures.local.folder("a")))
    }

    @Test
    fun afterAReinstallTheLibrarysLabelsAndMomentsComeBack() {
        val old = LectureStore(folder, "/old".toPath())
        // What the library held before: written as a previous install left it.
        LabelStore(folder, "/library".toPath()).create("Psych", 1)
        LectureStore(folder, "/library".toPath()).save(Lecture("kept", "Kept", "he", 1, 1, emptyList(), emptyList()))
        // A new install: a label made before the folder was picked again.
        labels.create("Stats", 2)

        adoptLibrary(lectures, labels, library)

        assertEquals(listOf("Psych", "Stats"), labels.all().map { it.name })
        assertEquals(listOf("kept"), lectures.all().map { it.id })
        assertEquals(emptyList(), old.all())
    }

    @Test
    fun lecturesStillInTheAppWhenTheLibraryHasTheLabelsKeepTheirs() {
        adoptLibrary(lectures, labels, library)
        // Before labels: notebooks.json was in the library; a lecture not yet archived was here in its notebook.
        val l = recorded("here")
        phone.createDirectories("/files/notebooks/psych".toPath())
        phone.atomicMove(lectures.local.folder("here"), "/files/notebooks/psych/here".toPath())
        VoiceStore(phone, "/files/notebooks/psych".toPath()).save(Voice("Dana", listOf(1f)))
        folder.write("/library/notebooks.json".toPath()) { writeUtf8("""{"v": 1, "notebooks": [{"id": "psych", "title": "Psych", "language": "he", "color": 1, "createdAt": 1}]}""") }

        migrateToLabels(lectures.local, 1, into = library)
        migrateToLabels(library, 1)

        assertEquals(listOf(l.id), lectures.all().map { it.id })
        assertEquals(listOf("psych"), lectures.load("here")?.labels)
        assertEquals(listOf("Psych"), labels.all().map { it.name })
        assertEquals(listOf("Dana"), voices.load().map { it.name })
        assertFalse(phone.exists("/files/labels.json".toPath()))
    }

    @Test
    fun aLibraryFromBeforeLabelsIsMovedToLabelsWhenPicked() {
        val inLibrary = LectureStore(folder, "/library".toPath())
        folder.createDirectories("/library".toPath())
        folder.write("/library/notebooks.json".toPath()) { writeUtf8("""{"v": 1, "notebooks": [{"id": "psych", "title": "Psych", "language": "he", "color": 2, "createdAt": 1}]}""") }
        inLibrary.save(Lecture("kept", "Kept", "he", 1, 1, emptyList(), emptyList()))
        folder.createDirectories("/library/notebooks/psych".toPath())
        folder.atomicMove("/library/moments/kept".toPath(), "/library/notebooks/psych/kept".toPath())

        adoptLibrary(lectures, labels, library)

        assertEquals(listOf(Label("psych", "Psych", 2)), labels.all())
        assertEquals(listOf("psych"), lectures.load("kept")?.labels)
        assertFalse(folder.exists("/library/notebooks.json".toPath()))
    }

    @Test
    fun changingTheFolderMovesEverythingIncludingTheOpenMoment() {
        val psych = labels.create("Psych", 1)
        voices.save(Voice("המרצה", listOf(0.5f)))
        recorded("a", psych.id)
        adoptLibrary(lectures, labels, library)
        val next = Place(folder, "/next".toPath())

        adoptLibrary(lectures, labels, next, busy = setOf("a"))

        assertEquals(next, lectures.library)
        assertEquals(listOf("Psych"), labels.all().map { it.name })
        assertEquals("המרצה", voices.load().single().name)
        assertEquals("/next/moments/a/audio.wav".toPath(), lectures.audioPath(assertNotNull(lectures.load("a"))))
        assertFalse(folder.exists(library.folder("a")))
        assertFalse(folder.exists("/library/labels.json".toPath()))
    }

    @Test
    fun aFailedCopyLeavesTheMomentHere() {
        adoptLibrary(lectures, labels, library)
        val a = recorded("a")
        folder.dropping = true
        assertEquals(0, archiveFinished(lectures, emptySet()))
        folder.dropping = false
        assertFalse(folder.exists(library.folder("a")))
        assertTrue(lectures.isLocal(assertNotNull(lectures.load("a"))))
        assertEquals("audio of a", phone.read(lectures.audioPath(a)) { readUtf8() })
    }

    @Test
    fun anUnreachableLibraryListsOnlyTheMomentsHere() {
        adoptLibrary(lectures, labels, library)
        recorded("a")
        archiveFinished(lectures, emptySet())
        recorded("b")
        lectures.library = Place(folder, "/gone".toPath())
        assertEquals(listOf("b"), lectures.all().map { it.id })
    }
}
