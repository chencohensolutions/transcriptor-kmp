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
import com.chencohensolution.transcriptor.notebook.Speaker
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.speech.LiveTranscriber
import com.chencohensolution.transcriptor.speech.SpeakerTracker
import com.chencohensolution.transcriptor.speech.Voice
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

/**
 * The live model for a lecture: its name, for the screen, the transcriber running it, and the speakers it tells apart
 * (null without a voice-print model).
 */
class LiveModel(val name: String, val transcriber: LiveTranscriber, val speakers: SpeakerTracker? = null)

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
    /** The notebooks' known voices: speakers the user named, labeled by name in later lectures. */
    private val voices: VoiceStore? = null,
    /**
     * Starts live transcription for a language, with the voices the notebook knows; null when no live model is
     * downloaded for it.
     */
    private val live: (language: String, scope: CoroutineScope, known: List<Voice>) -> LiveModel? = { _, _, _ -> null },
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
        names = emptyMap()
        liveModel = live(notebook.language, scope, voices?.load(notebook.id).orEmpty())?.also { model ->
            recorder.onPcm = model.transcriber::feed
            following = scope.launch {
                model.transcriber.output.collect { out ->
                    liveOutput.value = out
                    val r = recording.value ?: return@collect
                    if (out.segments.size != r.lecture.segments.size || out.speakers.size != r.lecture.speakers.size) {
                        recording.update { it?.copy(lecture = it.lecture.copy(segments = out.segments, speakers = named(out.speakers))) }
                        saveNow()
                    }
                }
            }
        }
        recorder.start(
            store.audioPath(lecture),
            if (format == AudioFormat.FlacLive) AudioFileFormat.Flac else AudioFileFormat.Wav,
            settings.flacLevel,
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

    /**
     * Names a speaker ("Lecturer"): in this lecture, and in the notebook's voices, so its next lectures label them by
     * name from the start.
     */
    fun renameSpeaker(id: String, name: String) {
        val r = recording.value ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        val old = r.lecture.speakers.firstOrNull { it.id == id }?.name.orEmpty()
        names = names + (id to clean)
        recording.update { it?.copy(lecture = it.lecture.copy(speakers = named(it.lecture.speakers))) }
        saveNow()
        val print = liveModel?.speakers?.print(id) ?: return
        if (old.isNotEmpty() && old != clean) voices?.rename(r.lecture.notebookId, old, clean)
        voices?.save(r.lecture.notebookId, Voice(clean, print))
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
            val out = transcriber.output.value
            recording.update { it?.copy(lecture = it.lecture.copy(segments = out.segments, speakers = named(out.speakers))) }
            // The named speakers' voices, as this whole lecture heard them, for the notebook's next lectures.
            val tracker = liveModel?.speakers
            if (tracker != null && voices != null) {
                for (s in recording.value!!.lecture.speakers) {
                    if (s.name.isNotEmpty()) tracker.print(s.id)?.let { voices.save(r.lecture.notebookId, Voice(s.name, it)) }
                }
            }
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

    /** Names the user gave this lecture's speakers, over the tracker's (known voices' names, or none). */
    // Replaced, not changed in place: the UI's thread writes it, live transcription's reads it.
    @kotlin.concurrent.Volatile
    private var names = mapOf<String, String>()

    private fun named(speakers: List<Speaker>) = speakers.map { s -> names[s.id]?.let { s.copy(name = it) } ?: s }

    private fun current() = recording.value?.lecture?.copy(durationMillis = recorder.positionMillis.value)

    // Notes save from the UI's thread and lines from live transcription's: one write at a time.
    private val saveLock = SynchronizedObject()
    private fun save(lecture: Lecture) = synchronized(saveLock) { store.save(lecture) }
    private fun saveNow() { current()?.let(::save) }

    companion object {
        const val SAVE_EVERY_MILLIS = 30_000L
    }
}
