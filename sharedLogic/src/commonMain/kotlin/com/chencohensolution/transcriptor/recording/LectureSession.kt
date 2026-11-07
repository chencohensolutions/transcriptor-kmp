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
import com.chencohensolution.transcriptor.speech.LiveSpeakers
import com.chencohensolution.transcriptor.speech.liveLines
import com.chencohensolution.transcriptor.notebook.Segment
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
 * The live model for a lecture: its name, for the screen, the transcriber running it, the speakers it tells apart
 * (null without a voice-print model), and the voice-print model with where it runs once loaded ([voicePrints]; the
 * platform sets [ModelInUse.runs] when it loads it).
 */
class LiveModel(
    val name: String,
    val transcriber: LiveTranscriber,
    val speakers: SpeakerTracker? = null,
    val voicePrints: MutableStateFlow<ModelInUse>? = null,
    /** Speakers found live with segmentation ([LiveSpeakers]); null: by each line's print ([speakers]' labeler). */
    val liveSpeakers: LiveSpeakers? = null,
    /** [liveSpeakers]' segmentation model (its id); null: pyannote's. */
    val turns: String? = null,
)

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
    /** The speaker pass's models are on the phone (it can run after Stop). */
    private val speakerPassReady: () -> Boolean = { false },
    /** Samples the battery while a lecture records, for its `power.json` and `power-log.csv`; null: not measured. */
    private val power: PowerMeter? = null,
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

    /** The lines the user fixed while recording, by line id: laid over live transcription's lines ([shown]). */
    private var edits: Map<String, String> = emptyMap()

    val state: StateFlow<LiveLectureState?> =
        combine(recording, recorder.positionMillis, recorder.level, liveOutput) { r, position, level, out ->
            r?.let {
                LiveLectureState(
                    notebookTitle = it.notebookTitle,
                    lecture = it.lecture.copy(durationMillis = if (it.finishing) it.lecture.durationMillis else position),
                    partial = out?.partial, elapsedMillis = position, level = if (it.paused) 0f else level,
                    paused = it.paused, liveModelName = liveModel?.name, lagMillis = out?.lagMillis,
                    liveLoading = out?.loading == true, liveError = out?.error, finishing = it.finishing,
                    voicePrints = liveModel?.voicePrints?.value,
                    speakerPassOnStop = liveModel?.voicePrints != null && settings.findSpeakersOnStop && speakerPassReady(),
                    speakersLive = liveModel?.liveSpeakers != null,
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
        edits = emptyMap()
        liveModel = live(notebook.language, scope, voices?.load(notebook.id).orEmpty())?.also { model ->
            val live = model.liveSpeakers
            recorder.onPcm = if (live == null) model.transcriber::feed else { pcm, size -> model.transcriber.feed(pcm, size); live.feed(pcm, size) }
            following = scope.launch {
                val speakers = live?.state ?: MutableStateFlow(LiveSpeakers.State())
                combine(model.transcriber.output, speakers) { out, found -> out to found }.collect { (out, found) ->
                    liveOutput.value = out
                    val r = recording.value ?: return@collect
                    val (lines, who) = shown(model, out, found)
                    if (lines != r.lecture.segments || who.size != r.lecture.speakers.size) {
                        recording.update { it?.copy(lecture = it.lecture.copy(segments = lines, speakers = named(who))) }
                        if (lines.size != r.lecture.segments.size || who.size != r.lecture.speakers.size) saveNow()
                    }
                }
            }
        }
        power?.start(scope)
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
        val tracker = liveModel?.speakers ?: return
        val print = tracker.print(id) ?: return
        if (old.isNotEmpty() && old != clean) voices?.rename(r.lecture.notebookId, old, clean)
        voices?.save(r.lecture.notebookId, Voice(clean, print, tracker.model))
    }

    /** The models this lecture used and where, for its power report. */
    private fun modelsInUse(): List<PowerModel> {
        val m = liveModel ?: return emptyList()
        val out = mutableListOf(PowerModel("live", m.name, if ("NPU" in m.name) "NPU" else "CPU"))
        m.voicePrints?.value?.let { v ->
            val where = when (v.runs) {
                ModelInUse.Runs.Npu -> "NPU"
                ModelInUse.Runs.CpuAfterNpuFailed -> "CPU after NPU failed"
                ModelInUse.Runs.Cpu -> "CPU"
                ModelInUse.Runs.NotYet -> "not loaded"
            }
            out += PowerModel("speakers", v.name, where)
        }
        return out
    }

    /** The lines and speakers to show: the transcriber's, or with [LiveSpeakers] its lines split and labeled by [found]. */
    private fun labeled(model: LiveModel, out: LiveOutput, found: LiveSpeakers.State): Pair<List<Segment>, List<Speaker>> {
        if (model.liveSpeakers == null) return out.segments to out.speakers
        return liveLines(out.segments, found.turns, found.settledMillis) to (model.speakers?.speakers ?: emptyList())
    }

    /**
     * The lines as the user sees them: [labeled], with the lines the user fixed ([editLine]) in their words. Live lines
     * are made again from the model's output as it goes, so the fixes are kept apart and laid over them every time:
     * before the lines are split by speaker (an edited line keeps its speaker split as it was, by time, not by words),
     * and after (a fix to a piece of a split line).
     */
    private fun shown(model: LiveModel, out: LiveOutput, found: LiveSpeakers.State): Pair<List<Segment>, List<Speaker>> {
        val (lines, who) = labeled(model, out.copy(segments = withEdits(out.segments)), found)
        return withEdits(lines) to who
    }

    private fun withEdits(lines: List<Segment>): List<Segment> =
        if (edits.isEmpty()) lines
        else lines.map { line -> edits[line.id]?.let { text -> line.copy(text = text, edited = true, words = emptyList()) } ?: line }

    /**
     * The user's words for a finished line while recording: kept over what live transcription makes of it from now on,
     * saved, and marked edited (the improve pass leaves it alone). Blank text changes nothing.
     */
    fun editLine(id: String, text: String) {
        val clean = text.trim()
        val r = recording.value ?: return
        if (clean.isEmpty() || r.lecture.segments.none { it.id == id }) return
        edits = edits + (id to clean)
        recording.update { it?.copy(lecture = it.lecture.copy(segments = withEdits(it.lecture.segments))) }
        saveNow()
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
        liveModel?.let { model ->
            model.transcriber.finish()
            model.liveSpeakers?.finish()
            following?.cancel()
            val (lines, who) = shown(model, model.transcriber.output.value, model.liveSpeakers?.state?.value ?: LiveSpeakers.State())
            recording.update { it?.copy(lecture = it.lecture.copy(segments = lines, speakers = named(who))) }
            // The named speakers' voices, as this whole lecture heard them, for the notebook's next lectures.
            val tracker = liveModel?.speakers
            if (tracker != null && voices != null) {
                for (s in recording.value!!.lecture.speakers) {
                    if (s.name.isNotEmpty()) tracker.print(s.id)?.let { voices.save(r.lecture.notebookId, Voice(s.name, it, tracker.model)) }
                }
            }
        }
        // How much power it took: power.json beside the audio, a row in power-log.csv.
        power?.let { meter ->
            runCatching {
                val report = PowerReport.of(meter.stop(), duration, modelsInUse())
                store.savePower(r.lecture, report)
                store.appendPowerLog(PowerLogCsv.row(r.lecture.id, r.lecture.notebookId, r.lecture.startedAtMillis, report))
            }
        }
        // What live speaker turns decided, for regrouping or tuning on this lecture later.
        liveModel?.let { m ->
            val found = m.liveSpeakers?.state?.value
            if (found != null && found.stretches.isNotEmpty()) {
                runCatching { store.saveLiveSpeakers(r.lecture, m.speakers?.model.orEmpty(), found.stretches, m.turns) }
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
