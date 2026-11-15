package com.chencohensolution.transcriptor.recording

import com.chencohensolution.transcriptor.notebook.AUDIO_AAC
import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.LiveOff
import com.chencohensolution.transcriptor.notebook.Pause
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
    /** The live model picked that didn't fit in the free memory now (its name), passed over for this one this time. */
    val skipped: String? = null,
    /** With [skipped]: the moment is improved as soon as it stops ([ImproveAfterStop]), a model to improve with being here. */
    val improveOnStop: Boolean = false,
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
    /** The app's voices: the speakers the user named are saved here. They are not used to name speakers in later moments. */
    private val voices: VoiceStore? = null,
    /** The speaker pass's models are on the phone (it can run after Stop). */
    private val speakerPassReady: () -> Boolean = { false },
    /** Samples the battery while a lecture records, for its `power.json` and `power-log.csv`; null: not measured. */
    private val power: PowerMeter? = null,
    /** A live model is on the phone for the language (live transcription can be switched on); doesn't load it. */
    private val liveAvailable: (language: String) -> Boolean = { false },
    /**
     * Starts live transcription for a language, with the voices the app knows; null when no live model is
     * downloaded for it.
     */
    private val live: (language: String, scope: CoroutineScope, known: List<Voice>) -> LiveModel? = { _, _, _ -> null },
) {
    private data class Recording(
        val labelNames: String,
        val lecture: Lecture,
        val paused: Boolean,
        val finishing: Boolean = false,
        val transcribeAfter: Boolean = true,
        /** Live transcription is on now (switched off while recording: false). */
        val transcribing: Boolean = false,
        /** A live model is on the phone for its language: transcription can be switched on (read once, at the start). */
        val canTranscribe: Boolean = false,
    )

    private val recording = MutableStateFlow<Recording?>(null)
    private val liveOutput = MutableStateFlow<LiveOutput?>(null)
    private var liveModel: LiveModel? = null
    private var wantedInput: MicInput? = null
    private var saving: Job? = null
    private var following: Job? = null

    // Live transcription switched off and on while recording: the transcriber only hears the audio while it is on,
    // so its times are in "heard" time. Each time it is switched on, the audio's position minus the heard time is
    // added from there on ([toAudio]), so every line keeps its place in the audio.
    @kotlin.concurrent.Volatile private var feeding = false
    @kotlin.concurrent.Volatile private var heardFrames = 0L
    private var shifts: List<Pair<Long, Long>> = listOf(0L to 0L)

    /**
     * Loads live transcription for [language] and feeds it the recorder's audio while [feeding]; null when no live
     * model is on the phone. Its lines follow into the recording as they come.
     */
    private fun startLive(language: String): LiveModel? = live(language, scope, emptyList())?.also { model ->
        val live = model.liveSpeakers
        recorder.onPcm = { pcm, size ->
            if (feeding) {
                model.transcriber.feed(pcm, size)
                live?.feed(pcm, size)
                heardFrames += size / 4
            }
        }
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

    /**
     * Switches live transcription off or on while recording. Off: the line being said ends and the model hears nothing
     * (it stays loaded, so on is quick); the stretch is kept with its clock times ([Lecture.liveOff]) and transcribed
     * after. On in a moment recorded without it (After, or No transcript): the language's live model loads now, and the
     * moment so far is a stretch that was off. Nothing when no live model is on the phone for the language.
     */
    fun setTranscribing(on: Boolean) {
        val r = recording.value ?: return
        if (r.finishing || r.transcribing == on) return
        val at = recorder.positionMillis.value
        val clock = now()
        if (on) {
            val model = liveModel ?: startLive(r.lecture.language)?.also { liveModel = it } ?: return
            val heard = heardFrames * 1000 / WavWriter.SAMPLE_RATE
            shifts = shifts + (heard to at - heard)
            feeding = true
            model.transcriber.pause()
            recording.update { rec ->
                rec ?: return@update null
                val offs = rec.lecture.liveOff
                val open = offs.lastOrNull()?.takeIf { it.toMillis == null }
                val next = if (open != null) offs.dropLast(1) + open.copy(toMillis = at, onAtMillis = clock)
                    else offs + LiveOff(0, at, rec.lecture.startedAtMillis, clock)
                rec.copy(transcribing = true, lecture = rec.lecture.copy(liveOff = next, pendingTranscript = false))
            }
        } else {
            feeding = false
            liveModel?.transcriber?.pause()
            recording.update { rec ->
                rec ?: return@update null
                rec.copy(transcribing = false, lecture = rec.lecture.copy(liveOff = rec.lecture.liveOff + LiveOff(at, null, clock)))
            }
        }
        saveNow()
    }

    /** Live lines (heard time) at their place in the audio: shifted by what wasn't heard before them. */
    private fun toAudio(lines: List<Segment>): List<Segment> {
        if (shifts.size == 1) return lines
        return lines.map { line ->
            val add = shifts.last { it.first <= line.startMillis }.second
            if (add == 0L) line
            else line.copy(startMillis = line.startMillis + add, endMillis = line.endMillis + add, words = line.words.map { it.copy(atMillis = it.atMillis + add) })
        }
    }

    /** The lines the user fixed while recording, by line id: laid over live transcription's lines ([shown]). */
    private var edits: Map<String, String> = emptyMap()

    val state: StateFlow<LiveLectureState?> =
        combine(recording, recorder.positionMillis, recorder.meter, liveOutput, recorder.inputInUse) { r, position, meter, out, input ->
            r?.let {
                LiveLectureState(
                    labelNames = it.labelNames,
                    lecture = it.lecture.copy(durationMillis = if (it.finishing) it.lecture.durationMillis else position),
                    partial = out?.partial, elapsedMillis = position, level = if (it.paused) 0f else meter.now.level,
                    stereoMeter = if (it.paused) meter.silenced() else meter,
                    input = input, inputMissing = wantedInput?.takeIf { w -> input?.name != w.name }?.name,
                    paused = it.paused, liveModelName = liveModel?.name, lagMillis = out?.lagMillis,
                    liveLoading = out?.loading == true, liveError = out?.error, finishing = it.finishing,
                    voicePrints = liveModel?.voicePrints?.value,
                    speakerPassOnStop = liveModel?.voicePrints != null && settings.findSpeakersOnStop && speakerPassReady(),
                    speakersLive = liveModel?.liveSpeakers != null,
                    skippedForMemory = liveModel?.skipped,
                    improveOnStop = liveModel?.skipped != null && liveModel?.improveOnStop == true,
                    recordOnly = liveModel == null || !it.transcribing, transcribeAfter = it.transcribeAfter,
                    transcribing = it.transcribing, canTranscribe = it.canTranscribe,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val isRecording get() = recording.value != null

    /**
     * The sound meter also works out the frequency bands ([StereoMeterState.bands]): set while the recording screen
     * shows a frequency style, so the small FFT runs only then.
     */
    var meterBands: Boolean
        get() = recorder.wantBands
        set(value) { recorder.wantBands = value }

    /**
     * Starts a moment with these [labels] (ids; [labelNames] for the screen) in [language]. Without [transcribeLive], or
     * with no live model for the language, it is only recorded ([Lecture.pendingTranscript]) and transcribed after,
     * unless not [transcribeAfter] ("Only record": the platform leaves it for the user's "Transcribe now").
     */
    fun start(
        labels: List<String>, labelNames: String, language: String, title: String, transcribeLive: Boolean = true,
        transcribeAfter: Boolean = true,
    ): Lecture {
        check(recording.value == null) { "A lecture is already being recorded" }
        val startedAt = now()
        val format = settings.audioFormat
        val fresh = Lecture(
            id = "lecture-$startedAt", title = title, language = language, labels = labels,
            startedAtMillis = startedAt, durationMillis = 0, segments = emptyList(), notes = emptyList(),
            audioFile = when (format) {
                AudioFormat.FlacLive -> AUDIO_FLAC
                AudioFormat.AacLive -> AUDIO_AAC
                else -> AUDIO_WAV
            },
        )
        names = emptyMap()
        edits = emptyMap()
        // Every moment starts with unnamed speakers: a voice named in another moment isn't guessed here (owner's
        // decision, Nov 15, 2026: matching voices between moments wasn't accurate enough). The names are still kept
        // ([VoiceStore]), to be offered when the user names a speaker.
        shifts = listOf(0L to 0L)
        heardFrames = 0
        liveModel = if (transcribeLive) startLive(language) else null
        feeding = liveModel != null
        // Saved as waiting for its transcript from the start, so a moment cut short by a crash is still transcribed.
        val lecture = fresh.copy(pendingTranscript = liveModel == null)
        store.save(lecture)
        power?.start(scope)
        recorder.start(
            store.audioPath(lecture),
            when (format) {
                AudioFormat.FlacLive -> AudioFileFormat.Flac
                AudioFormat.AacLive -> AudioFileFormat.Aac
                else -> AudioFileFormat.Wav
            },
            settings.flacLevel,
            settings.aac,
            settings.micInput,
        )
        wantedInput = settings.micInput
        recording.value = Recording(labelNames, lecture, paused = false, transcribeAfter = transcribeAfter, transcribing = liveModel != null, canTranscribe = liveModel != null || liveAvailable(language))
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
        // The clock time of each pause and resume, kept with the moment: the audio goes on without a gap.
        val clock = now()
        recording.update { rec ->
            rec ?: return@update null
            val pauses = rec.lecture.pauses
            val next = if (r.paused) pauses.dropLast(1) + listOfNotNull(pauses.lastOrNull()?.copy(resumedAtMillis = clock))
                else pauses + Pause(recorder.positionMillis.value, clock)
            rec.copy(paused = !r.paused, lecture = rec.lecture.copy(pauses = next))
        }
        if (r.paused) saveNow()
        if (!r.paused) {
            liveModel?.transcriber?.pause()
            recorder.checkpoint()
            saveNow()
        }
    }

    /**
     * Names a speaker ("Lecturer"): in this moment, and in the app's voices, so the next moments label them by
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
        if (old.isNotEmpty() && old != clean) voices?.rename(old, clean)
        voices?.save(Voice(clean, print, tracker.model))
    }

    /** The models this lecture used and where, for its power report. */
    /** The audio's format and encoder for the power report, from the setting it was recorded with and the recorder. */
    private fun audioInUse(file: String): PowerAudio {
        val format = settings.audioFormat
        val codec = file.substringAfterLast('.')
        val aac = settings.aac
        val details = when (codec) {
            "aac" -> "${aac.profile.name} · ${aac.bitrateKbps} kbps · ${if (aac.channels == 1) "mono" else "stereo"} · ${aac.sampleRate / 1000} kHz"
            "flac" -> "level ${settings.flacLevel}"
            else -> when (format) {
                AudioFormat.WavThenAac -> "then AAC ${aac.profile.name} · ${aac.bitrateKbps} kbps, ${settings.encodeAfter.name.lowercase()}"
                AudioFormat.WavThenFlac -> "then FLAC level ${settings.flacLevel}, ${settings.encodeAfter.name.lowercase()}"
                else -> ""
            }
        }
        return PowerAudio(
            format = format.name, codec = codec, encoder = recorder.encoderName, hardware = recorder.encoderHardware, details = details,
        )
    }

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
        return toAudio(withEdits(lines)) to who
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
     * A mark at this second of the recording: a note without words ([Note.isMark]), made with one tap when typing
     * isn't possible. Returns its time; null when nothing is recording. Words can be added after, on the moment.
     */
    fun mark(): Long? {
        val r = recording.value ?: return null
        if (r.finishing) return null
        val at = recorder.positionMillis.value
        recording.update { rec ->
            val notes = rec?.lecture?.notes ?: return@update rec
            val id = "n${(notes.maxOfOrNull { it.id.removePrefix("n").toIntOrNull() ?: 0 } ?: 0) + 1}"
            rec.copy(lecture = rec.lecture.copy(notes = notes + Note(id, at, "")))
        }
        saveNow()
        return at
    }

    /**
     * Ends the lecture: the recording stops at once, then live transcription finishes the lines still waiting
     * ([LiveLectureState.finishing] meanwhile). With "WAV, then FLAC" or "WAV, then AAC" it is saved as [Lecture.pendingFlac] or [Lecture.pendingAac] for
     * [AudioCompression].
     */
    suspend fun stop(): Lecture? {
        val r = recording.value ?: return null
        if (r.finishing) return null
        saving?.cancel()
        val input = recorder.inputInUse.value?.name
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
            // The named speakers' voices, as this whole moment heard them, for the next moments.
            val tracker = liveModel?.speakers
            if (tracker != null && voices != null) {
                for (s in recording.value!!.lecture.speakers) {
                    if (s.name.isNotEmpty()) tracker.print(s.id)?.let { voices.save(Voice(s.name, it, tracker.model)) }
                }
            }
        }
        // How much power it took: power.json beside the audio, a row in power-log.csv.
        power?.let { meter ->
            runCatching {
                val report = PowerReport.of(meter.stop(), duration, modelsInUse(), audioInUse(r.lecture.audioFile).copy(input = input))
                store.savePower(r.lecture, report)
                store.appendPowerLog(PowerLogCsv.row(r.lecture.id, r.lecture.labels.joinToString("+"), r.lecture.startedAtMillis, report))
            }
        }
        // What live speaker turns decided, for regrouping or tuning on this lecture later.
        liveModel?.let { m ->
            val found = m.liveSpeakers?.state?.value
            // Kept only when it heard the whole moment: its stretches are in heard time.
            if (found != null && found.stretches.isNotEmpty() && r.lecture.liveOff.isEmpty() && recording.value?.lecture?.liveOff.isNullOrEmpty()) {
                runCatching { store.saveLiveSpeakers(r.lecture, m.speakers?.model.orEmpty(), found.stretches, m.turns) }
            }
        }
        val lecture = recording.value!!.lecture.let { l ->
            // Still off at Stop: off to the end.
            l.copy(liveOff = l.liveOff.map { if (it.toMillis == null) it.copy(toMillis = duration) else it })
        }.copy(
            durationMillis = duration,
            pendingFlac = r.lecture.audioFile == AUDIO_WAV && settings.audioFormat == AudioFormat.WavThenFlac,
            pendingAac = r.lecture.audioFile == AUDIO_WAV && settings.audioFormat == AudioFormat.WavThenAac,
        )
        save(lecture)
        recording.value = null
        liveOutput.value = null
        liveModel = null
        feeding = false
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
