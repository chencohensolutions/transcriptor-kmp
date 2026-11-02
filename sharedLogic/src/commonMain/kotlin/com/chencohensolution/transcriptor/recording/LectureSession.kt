package com.chencohensolution.transcriptor.recording

import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.settings.AudioFormat
import com.chencohensolution.transcriptor.settings.SettingsStore
import com.chencohensolution.transcriptor.speech.LiveOutput
import com.chencohensolution.transcriptor.speech.LiveTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The live model for a lecture: its name, for the screen, and the transcriber running it. */
class LiveModel(val name: String, val transcriber: LiveTranscriber)

/**
 * Recording one lecture: the microphone to the audio file ([AudioRecorder]), the lines from live transcription
 * ([live], when a model is downloaded for the lecture's language), the notes, and `lecture.json`
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
    /** Starts live transcription for a language; null when no live model is downloaded for it. */
    private val live: (language: String, scope: CoroutineScope) -> LiveModel? = { _, _ -> null },
) {
    private data class Recording(
        val notebookTitle: String,
        val lecture: Lecture,
        val paused: Boolean,
        val finishing: Boolean = false,
    )

    private val recording = MutableStateFlow<Recording?>(null)
    private val liveOutput = MutableStateFlow<LiveOutput?>(null)
    private var liveModel: LiveModel? = null
    private var saving: Job? = null
    private var following: Job? = null

    val state: StateFlow<LiveLectureState?> =
        combine(recording, recorder.positionMillis, recorder.level, liveOutput) { r, position, level, out ->
            r?.let {
                LiveLectureState(
                    notebookTitle = it.notebookTitle,
                    lecture = it.lecture.copy(durationMillis = if (it.finishing) it.lecture.durationMillis else position),
                    partial = out?.partial, elapsedMillis = position, level = if (it.paused) 0f else level,
                    paused = it.paused, liveModelName = liveModel?.name, lagMillis = out?.lagMillis,
                    liveLoading = out?.loading == true, liveError = out?.error, finishing = it.finishing,
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
        liveModel = live(notebook.language, scope)?.also { model ->
            recorder.onPcm = model.transcriber::feed
            following = scope.launch {
                model.transcriber.output.collect { out ->
                    liveOutput.value = out
                    val r = recording.value ?: return@collect
                    if (out.segments.size != r.lecture.segments.size) {
                        recording.update { it?.copy(lecture = it.lecture.copy(segments = out.segments)) }
                        saveNow()
                    }
                }
            }
        }
        recorder.start(
            store.audioPath(lecture),
            if (format == AudioFormat.FlacLive) AudioFileFormat.Flac else AudioFileFormat.Wav,
        )
        recording.value = Recording(notebook.title, lecture, paused = false)
        saving = scope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MILLIS)
                recorder.checkpoint()
                saveNow()
            }
        }
        return lecture
    }

    fun pauseOrResume() {
        val r = recording.value ?: return
        if (r.finishing) return
        if (r.paused) recorder.resume() else recorder.pause()
        recording.update { it?.copy(paused = !r.paused) }
        if (!r.paused) {
            liveModel?.transcriber?.pause()
            recorder.checkpoint()
            saveNow()
        }
    }

    /** A note pinned to [atMillis]: the position in the audio when the user started typing it. */
    fun addNote(atMillis: Long, text: String) {
        if (text.isBlank() || recording.value == null) return
        recording.update { r ->
            val notes = r?.lecture?.notes ?: return@update r
            val id = "n${(notes.maxOfOrNull { it.id.removePrefix("n").toIntOrNull() ?: 0 } ?: 0) + 1}"
            r.copy(lecture = r.lecture.copy(notes = notes + Note(id, atMillis, text.trim())))
        }
        saveNow()
    }

    /**
     * Ends the lecture: the recording stops at once, then live transcription finishes the lines still waiting
     * ([LiveLectureState.finishing] meanwhile). With "WAV, then FLAC" it is saved as [Lecture.pendingFlac] for
     * [FlacCompression].
     */
    suspend fun stop(): Lecture? {
        val r = recording.value ?: return null
        if (r.finishing) return null
        saving?.cancel()
        recorder.stop()
        recorder.onPcm = null
        val duration = recorder.positionMillis.value
        recording.update { it?.copy(lecture = it.lecture.copy(durationMillis = duration), finishing = true) }
        liveModel?.transcriber?.let { transcriber ->
            transcriber.finish()
            following?.cancel()
            val segments = transcriber.output.value.segments
            recording.update { it?.copy(lecture = it.lecture.copy(segments = segments)) }
        }
        val lecture = recording.value!!.lecture.copy(
            durationMillis = duration,
            pendingFlac = r.lecture.audioFile == AUDIO_WAV && settings.audioFormat == AudioFormat.WavThenFlac,
        )
        save(lecture)
        recording.value = null
        liveOutput.value = null
        liveModel = null
        return lecture
    }

    private fun current() = recording.value?.lecture?.copy(durationMillis = recorder.positionMillis.value)

    // Notes save from the UI's thread and lines from live transcription's: one write at a time.
    private val saveLock = SynchronizedObject()
    private fun save(lecture: Lecture) = synchronized(saveLock) { store.save(lecture) }
    private fun saveNow() { current()?.let(::save) }

    companion object {
        const val SAVE_EVERY_MILLIS = 30_000L
    }
}
