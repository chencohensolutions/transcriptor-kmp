package com.chencohensolution.transcriptor.recording

import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Recording one lecture: the microphone to the audio file ([AudioRecorder]), the notes, and `lecture.json`
 * ([LectureStore]), saved at the start, on every note, on pause and stop, and every [SAVE_EVERY_MILLIS] while
 * recording, so a crash loses at most that much of the duration (the audio is written as it records).
 * [state] is null when no lecture is being recorded.
 */
@OptIn(ExperimentalTime::class)
class LectureSession(
    private val recorder: AudioRecorder,
    private val store: LectureStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Recording(val notebookTitle: String, val lecture: Lecture, val paused: Boolean)

    private val recording = MutableStateFlow<Recording?>(null)
    private var saving: Job? = null

    val state: StateFlow<LiveLectureState?> =
        combine(recording, recorder.positionMillis, recorder.level) { r, position, level ->
            r?.let {
                LiveLectureState(
                    notebookTitle = it.notebookTitle, lecture = it.lecture.copy(durationMillis = position),
                    partial = null, elapsedMillis = position, level = if (it.paused) 0f else level,
                    paused = it.paused, liveModelName = null, lagMillis = null,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val isRecording get() = recording.value != null

    fun start(notebook: Notebook, title: String): Lecture {
        check(recording.value == null) { "A lecture is already being recorded" }
        val startedAt = now()
        val format = settings.audioFormat
        val lecture = Lecture(
            id = "lecture-$startedAt", notebookId = notebook.id, title = title, language = notebook.language,
            startedAtMillis = startedAt, durationMillis = 0, segments = emptyList(), notes = emptyList(),
            audioFile = if (format == AudioFormat.FlacLive) AUDIO_FLAC else AUDIO_WAV,
        )
        store.save(lecture)
        recorder.start(
            store.audioPath(lecture),
            if (format == AudioFormat.FlacLive) AudioFileFormat.Flac else AudioFileFormat.Wav,
        )
        recording.value = Recording(notebook.title, lecture, paused = false)
        saving = scope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MILLIS)
                recorder.checkpoint()
                current()?.let(store::save)
            }
        }
        return lecture
    }

    fun pauseOrResume() {
        val r = recording.value ?: return
        if (r.paused) recorder.resume() else recorder.pause()
        recording.value = Recording(r.notebookTitle, r.lecture, !r.paused)
        if (!r.paused) {
            recorder.checkpoint()
            current()?.let(store::save)
        }
    }

    /** A note pinned to [atMillis]: the position in the audio when the user started typing it. */
    fun addNote(atMillis: Long, text: String) {
        val r = recording.value ?: return
        val notes = r.lecture.notes
        val note = Note("n${(notes.maxOfOrNull { it.id.removePrefix("n").toIntOrNull() ?: 0 } ?: 0) + 1}", atMillis, text.trim())
        if (note.text.isEmpty()) return
        recording.value = Recording(r.notebookTitle, r.lecture.copy(notes = notes + note), r.paused)
        current()?.let(store::save)
    }

    /** Ends the lecture; with "WAV, then FLAC" it is saved as [Lecture.pendingFlac] for [FlacCompression]. */
    fun stop(): Lecture? {
        val r = recording.value ?: return null
        saving?.cancel()
        recorder.stop()
        val lecture = r.lecture.copy(
            durationMillis = recorder.positionMillis.value,
            pendingFlac = r.lecture.audioFile == AUDIO_WAV && settings.audioFormat == AudioFormat.WavThenFlac,
        )
        store.save(lecture)
        recording.value = null
        return lecture
    }

    private fun current() = recording.value?.lecture?.copy(durationMillis = recorder.positionMillis.value)

    companion object {
        const val SAVE_EVERY_MILLIS = 30_000L
    }
}
