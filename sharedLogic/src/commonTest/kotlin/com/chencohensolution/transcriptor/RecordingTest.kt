package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.recording.AudioFileFormat
import com.chencohensolution.transcriptor.recording.AudioRecorder
import com.chencohensolution.transcriptor.recording.FlacCompression
import com.chencohensolution.transcriptor.recording.FlacEncode
import com.chencohensolution.transcriptor.recording.LectureSession
import com.chencohensolution.transcriptor.recording.WavWriter
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Writes a WAV of silence as it "records": [advance] moves the position by writing that much audio. */
class FakeRecorder(private val fileSystem: FileSystem) : AudioRecorder {
    override val positionMillis = MutableStateFlow(0L)
    override val level = MutableStateFlow(0f)
    override var onPcm: ((ByteArray, Int) -> Unit)? = null
    var file: Path? = null
    var format: AudioFileFormat? = null
    var paused = false
    var checkpoints = 0
    private var wav: WavWriter? = null

    override fun start(file: Path, format: AudioFileFormat) {
        this.file = file
        this.format = format
        if (format == AudioFileFormat.Wav) wav = WavWriter(fileSystem, file) else fileSystem.write(file) {}
        level.value = 0.5f
    }

    /** [millis] of silence, or of what [pcm] gives (48 kHz stereo 16 bit). */
    fun advance(millis: Long, pcm: ByteArray = ByteArray((millis * 48 * 4).toInt())) {
        wav?.write(pcm, pcm.size)
        onPcm?.invoke(pcm, pcm.size)
        positionMillis.value += millis
    }

    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun checkpoint() { checkpoints++; wav?.checkpoint() }
    override fun stop() { wav?.close(); level.value = 0f }
}

class RecordingTest {
    // The recorder keeps its file open, as on the phone, while the tests read it.
    private val fs = FakeFileSystem().apply { allowReadsWhileWriting = true; allowWritesWhileWriting = true }
    private val root = "/data".toPath()
    private val store = LectureStore(fs, root)
    private val settings = SettingsStore(fs, root)
    private val recorder = FakeRecorder(fs)
    private val notebook = Notebook("psych", "פסיכולוגיה קוגניטיבית", "he", emptyList())

    private fun TestScope.session() = LectureSession(recorder, store, settings, backgroundScope, now = { 1_793_600_000_000 })

    @Test
    fun theDefaultIsWav() {
        assertEquals(AudioFormat.Wav, settings.audioFormat)
        settings.audioFormat = AudioFormat.FlacLive
        assertEquals(AudioFormat.FlacLive, SettingsStore(fs, root).audioFormat)
        assertNull(settings.liveModel("he"))
        settings.setLiveModel("he", "ivrit-zipformer2-66m-streaming")
        assertEquals("ivrit-zipformer2-66m-streaming", SettingsStore(fs, root).liveModel("he"))
        assertEquals(AudioFormat.FlacLive, SettingsStore(fs, root).audioFormat)
    }

    @Test
    fun theSpeakersAreFoundOnStopUntilTurnedOffAndNothingImprovesOnItsOwn() {
        assertTrue(settings.findSpeakersOnStop)
        assertFalse(settings.autoImprove)
        settings.findSpeakersOnStop = false
        settings.autoImprove = true
        assertEquals(false, SettingsStore(fs, root).findSpeakersOnStop)
        assertEquals(true, SettingsStore(fs, root).autoImprove)
        assertEquals(AudioFormat.Wav, SettingsStore(fs, root).audioFormat)
    }

    @Test
    fun aSettingsFileFromBeforeTheSplitKeepsItsSpeakerSwitch() {
        fs.createDirectories(root)
        fs.write(root / "settings.json") { writeUtf8("""{"v":1,"audioFormat":"wav","afterLecture":false}""") }
        assertFalse(SettingsStore(fs, root).findSpeakersOnStop)
        assertFalse(SettingsStore(fs, root).autoImprove)
    }

    @Test
    fun anImproveModelsSpeedAndTheLecturesWaitingAreKept() {
        assertNull(settings.improveSpeed("ivrit-whisper-large-v3"))
        settings.recordImproveSpeed("ivrit-whisper-large-v3", workMillis = 1_200_000, audioMillis = 3_000_000)
        assertEquals(0.4, SettingsStore(fs, root).improveSpeed("ivrit-whisper-large-v3"))
        settings.addImprovePending("psych", "l1")
        settings.addImprovePending("psych", "l2")
        settings.addImprovePending("psych", "l1")
        assertEquals(listOf("psych" to "l1", "psych" to "l2"), SettingsStore(fs, root).improvePending)
        settings.removeImprovePending("psych", "l1")
        assertEquals(listOf("psych" to "l2"), SettingsStore(fs, root).improvePending)
    }

    @Test
    fun startCreatesTheFolderTheJsonAndTheAudio() = runTest {
        val lecture = session().start(notebook, "זיכרון עבודה")
        val folder = store.folder("psych", lecture.id)
        assertTrue(fs.exists(folder / "lecture.json"))
        assertEquals(folder / AUDIO_WAV, recorder.file)
        assertEquals(AudioFileFormat.Wav, recorder.format)
        assertEquals("זיכרון עבודה", store.load("psych", lecture.id)?.title)
    }

    @Test
    fun flacLiveRecordsToFlac() = runTest {
        settings.audioFormat = AudioFormat.FlacLive
        val lecture = session().start(notebook, "t")
        assertEquals(AUDIO_FLAC, lecture.audioFile)
        assertEquals(AudioFileFormat.Flac, recorder.format)
    }

    @Test
    fun notesKeepTheirTimeAndAreSavedAtOnce() = runTest {
        val session = session()
        val lecture = session.start(notebook, "t")
        recorder.advance(5_000)
        session.addNote(3_000, "  פסיבי ↔ אקטיבי ")
        session.addNote(4_000, "   ")
        runCurrent()
        val notes = store.load("psych", lecture.id)!!.notes
        assertEquals(1, notes.size)
        assertEquals(3_000, notes[0].atMillis)
        assertEquals("פסיבי ↔ אקטיבי", notes[0].text)
        assertEquals(1, session.state.value?.lecture?.notes?.size)
    }

    @Test
    fun pauseAndResumeMoveTheState() = runTest {
        val session = session()
        session.start(notebook, "t")
        session.pauseOrResume()
        runCurrent()
        assertTrue(recorder.paused)
        assertEquals(true, session.state.value?.paused)
        assertEquals(0f, session.state.value?.level)
        session.pauseOrResume()
        runCurrent()
        assertFalse(recorder.paused)
        assertEquals(false, session.state.value?.paused)
    }

    @Test
    fun theDurationIsSavedWhileRecordingAndOnStop() = runTest {
        val session = session()
        val lecture = session.start(notebook, "t")
        recorder.advance(31_000)
        advanceTimeBy(LectureSession.SAVE_EVERY_MILLIS + 1)
        assertEquals(31_000, store.load("psych", lecture.id)?.durationMillis)
        assertEquals(1, recorder.checkpoints)
        recorder.advance(9_000)
        val stopped = assertNotNull(session.stop())
        runCurrent()
        assertEquals(40_000, stopped.durationMillis)
        assertEquals(40_000, store.load("psych", lecture.id)?.durationMillis)
        assertFalse(stopped.pendingFlac)
        assertNull(session.state.value)
        assertEquals(40_000 * 48, WavWriter.read(fs, store.audioPath(stopped)).frames)
    }

    @Test
    fun theJsonHasTheDocumentedFields() = runTest {
        val session = session()
        val lecture = session.start(notebook, "t")
        session.addNote(1_000, "n")
        val text = fs.read(store.folder("psych", lecture.id) / "lecture.json") { readUtf8() }
        listOf("\"v\": 1", "\"startedAt\": \"2026-11-02T", "\"durationMs\"", "\"audio\": \"audio.wav\"", "\"atMs\": 1000")
            .forEach { assertTrue(it in text, "$it in $text") }
    }

    @Test
    fun wavRepairFixesTheSizesACrashLeft() {
        val path = "/a.wav".toPath()
        val wav = WavWriter(fs, path)
        wav.write(ByteArray(4_800), 4_800)
        wav.checkpoint()
        wav.write(ByteArray(9_600), 9_600) // never checkpointed: the crash
        assertEquals(1_200, WavWriter.read(fs, path).frames)
        assertTrue(WavWriter.repair(fs, path))
        val info = WavWriter.read(fs, path)
        assertEquals(3_600, info.frames)
        assertEquals(48_000, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertFalse(WavWriter.repair(fs, path))
    }

    @Test
    fun wavThenFlacIsCompressedAfterStop() = runTest {
        settings.audioFormat = AudioFormat.WavThenFlac
        val session = session()
        session.start(notebook, "t")
        recorder.advance(2_000)
        val stopped = assertNotNull(session.stop())
        assertTrue(stopped.pendingFlac)
        val compression = FlacCompression(fs, store) { _, flac, progress ->
            fs.write(flac) { writeUtf8("fLaC") }
            progress(1f)
            96_000
        }
        assertEquals(listOf(stopped.id), compression.pending().map { it.id })
        val done = compression.compress(stopped)
        assertEquals(AUDIO_FLAC, done.audioFile)
        assertEquals(AUDIO_FLAC, store.load("psych", stopped.id)?.audioFile)
        assertFalse(fs.exists(store.audioPath(stopped, AUDIO_WAV)))
        assertTrue(compression.pending().isEmpty())
    }

    @Test
    fun aFailedCompressionKeepsTheWav() = runTest {
        settings.audioFormat = AudioFormat.WavThenFlac
        val session = session()
        session.start(notebook, "t")
        recorder.advance(1_000)
        val stopped = assertNotNull(session.stop())
        val compression = FlacCompression(fs, store) { _, flac, _ -> fs.write(flac) {}; 10 }
        assertFailsWith<IllegalStateException> { compression.compress(stopped) }
        assertTrue(fs.exists(store.audioPath(stopped, AUDIO_WAV)))
        assertEquals(true, store.load("psych", stopped.id)?.pendingFlac)
    }
}

class LiveRecordingTest {
    private val fs = FakeFileSystem().apply { allowReadsWhileWriting = true; allowWritesWhileWriting = true }
    private val root = "/data".toPath()
    private val store = LectureStore(fs, root)
    private val recorder = FakeRecorder(fs)
    private val notebook = Notebook("psych", "פסיכולוגיה", "he", emptyList())

    @Test
    fun linesFromLiveTranscriptionAreSavedAndStopWaitsForTheLast() = runTest {
        val engine = FakeEngine()
        val session = LectureSession(recorder, store, SettingsStore(fs, root), backgroundScope, now = { 1L }) { language, scope, _ ->
            com.chencohensolution.transcriptor.recording.LiveModel(
                "fake",
                com.chencohensolution.transcriptor.speech.UtteranceTranscriber(language, "m", scope, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { engine },
            )
        }
        val lecture = session.start(notebook, "t")
        recorder.advance(500, silence(500))
        recorder.advance(3_500, tone(3_500))
        recorder.advance(1_000, silence(1_000))
        runCurrent()
        assertEquals("fake", session.state.value?.liveModelName)
        assertEquals(listOf("line 1"), store.load("psych", lecture.id)?.segments?.map { it.text })
        recorder.advance(900, tone(900))
        val stopped = assertNotNull(session.stop())
        assertEquals(listOf("line 1", "line 2"), stopped.segments.map { it.text })
        assertEquals(listOf("line 1", "line 2"), store.load("psych", lecture.id)?.segments?.map { it.text })
        assertTrue(engine.closed)
    }
}
