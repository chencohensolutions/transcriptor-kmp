package com.chencohensolution.transcriptor

import com.chencohensolution.shed.android.AndroidShed
import com.chencohensolution.shed.android.ShedHost
import com.chencohensolution.shed.android.ShedUploads
import com.chencohensolution.transcriptor.backup.PrivateResult
import com.chencohensolution.transcriptor.backup.ServerBackup
import kotlinx.coroutines.cancel
import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import okio.Path
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.LabelStore
import com.chencohensolution.transcriptor.notebook.NOTEBOOKS_FILE
import com.chencohensolution.transcriptor.notebook.migrateToLabels
import com.chencohensolution.transcriptor.notebook.Place
import com.chencohensolution.transcriptor.notebook.adoptLibrary
import com.chencohensolution.transcriptor.notebook.archiveFinished
import com.chencohensolution.transcriptor.storage.LIBRARY_ROOT
import com.chencohensolution.transcriptor.storage.SafFileSystem
import com.chencohensolution.transcriptor.notebook.NotebooksController
import com.chencohensolution.transcriptor.recording.AndroidRecorder
import com.chencohensolution.transcriptor.recording.AudioCompression
import com.chencohensolution.transcriptor.settings.allows
import com.chencohensolution.transcriptor.recording.LectureSession
import com.chencohensolution.transcriptor.recording.androidAacEncode
import com.chencohensolution.transcriptor.recording.androidFlacEncode
import com.chencohensolution.transcriptor.models.HubClient
import com.chencohensolution.transcriptor.models.InstalledModels
import com.chencohensolution.transcriptor.models.Languages
import com.chencohensolution.transcriptor.models.ModelLibrary
import com.chencohensolution.transcriptor.models.NpuResult
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelChoices
import com.chencohensolution.transcriptor.models.ModelJob
import com.chencohensolution.transcriptor.recording.LiveModel
import com.chencohensolution.transcriptor.settings.SettingsStore
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.models.NpuLoadGuard
import com.chencohensolution.transcriptor.recording.ModelInUse
import com.chencohensolution.transcriptor.recording.PowerMeter
import com.chencohensolution.transcriptor.speech.VoicePrintFiles
import com.chencohensolution.transcriptor.speech.LiveSpeakers
import com.chencohensolution.transcriptor.speech.SherpaSegmenter
import java.io.File
import com.chencohensolution.transcriptor.speech.VoicePrintThresholds
import com.chencohensolution.transcriptor.review.AndroidAudioPlayer
import com.chencohensolution.transcriptor.review.LectureReview
import com.chencohensolution.transcriptor.speech.RecordingAudio
import com.chencohensolution.transcriptor.speech.SherpaDiarizer
import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine
import com.chencohensolution.transcriptor.speech.SherpaStreamingEngine
import com.chencohensolution.transcriptor.speech.RefinePass
import com.chencohensolution.transcriptor.speech.transcribeLineStreaming
import com.chencohensolution.transcriptor.speech.SpeakerPass
import com.chencohensolution.transcriptor.speech.withTurns
import com.chencohensolution.transcriptor.speech.SherpaVoiceEmbedder
import com.chencohensolution.transcriptor.speech.SpeakerLabeler
import com.chencohensolution.transcriptor.speech.SpeakerTracker
import com.chencohensolution.transcriptor.speech.Voice
import com.chencohensolution.transcriptor.speech.StreamingTranscriber
import com.chencohensolution.transcriptor.speech.UtteranceTranscriber
import com.chencohensolution.transcriptor.speech.UtteranceLimits
import com.chencohensolution.transcriptor.speech.lineTimes
import com.chencohensolution.transcriptor.speech.transcribed
import com.chencohensolution.transcriptor.speech.ModelFiles
import com.chencohensolution.transcriptor.speech.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * The app's one recording session and what it needs, shared by [RecordingService] (which keeps it going with the
 * screen off) and [MainActivity] (which shows it). Files are in the app's private storage, `files/`.
 */
/** The folder made in the one the user picks, unless it is one already. */
private const val LIBRARY_FOLDER = "Audio Logs"

/** The folder's name before the app was named Audio Logs: still a library when picked, and renamed when it can be. */
private const val OLD_LIBRARY_FOLDER = "Transcriptor"

object Recording {
    lateinit var store: LectureStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var session: LectureSession

    /** The microphones besides the phone's own, for Settings' Microphone and the recorder. */
    lateinit var audioInputs: com.chencohensolution.transcriptor.recording.AndroidAudioInputs
        private set
    lateinit var compression: AudioCompression
        private set
    lateinit var models: ModelFiles
        private set

    /** The app's unpacked native libraries: Qualcomm's NPU runtime for [ModelKind.WhisperNpu]. */
    lateinit var nativeLibDir: String
        private set

    /** The last lecture stopped, for the start screen. */
    val lastLecture = MutableStateFlow<Lecture?>(null)

    /** Stop by place and the path while a moment records (Settings › Advanced › Location); null when neither is on for it. */
    val placeStatus = MutableStateFlow<com.chencohensolution.transcriptor.location.MomentLocation.Status?>(null)

    /** The refine pass: the lecture it runs on and 0..1; null when not running. */
    val refining = MutableStateFlow<Pair<String, Float>?>(null)

    /** Encoding a WAV to FLAC or AAC: the lecture and 0..1, null when not. */
    val compressing = MutableStateFlow<Pair<String, Float>?>(null)

    /** The battery and process readings behind the power reports (recording, and encoding afterwards). */
    private lateinit var powerProbe: com.chencohensolution.transcriptor.recording.PowerProbe

    /** Held while lectures are encoded (by [RecordingService] or [EncodeJob]), so only one runs. */
    val encoding = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The power report of encoding [lecture]'s WAV ([samples] over [millis]): `power-encode.json` and a row in power-log.csv. */
    private fun logEncode(lecture: Lecture, toAac: Boolean, samples: List<com.chencohensolution.transcriptor.recording.PowerSample>, millis: Long) {
        val profile = settings.profileFor(lecture.profile)
        val aac = profile.aac
        val encoder = if (toAac) com.chencohensolution.transcriptor.recording.AacEncoders.lastUsed else "c2.android.flac.encoder"
        val audio = com.chencohensolution.transcriptor.recording.PowerAudio(
            format = (if (toAac) "WavThenAac" else "WavThenFlac") + " · ${lecture.profile ?: "-"}", codec = if (toAac) "aac" else "flac", encoder = encoder,
            hardware = com.chencohensolution.transcriptor.recording.AacEncoders.all.any { it.name == encoder && it.hardware },
            details = (if (toAac) "${aac.profile.name} · ${aac.bitrateKbps} kbps · ${if (aac.channels == 1) "mono" else "stereo"} · ${aac.sampleRate / 1000} kHz"
                else "level ${profile.flacLevel} · ${profile.flac.bitDepth} bit · ${profile.flac.sampleRate / 1000} kHz") + " · ${lecture.durationMillis / 60_000} min of audio · ${settings.encodeAfter.name.lowercase()}",
        )
        val report = com.chencohensolution.transcriptor.recording.PowerReport.of(samples, millis, emptyList(), audio, com.chencohensolution.transcriptor.recording.PowerReport.PHASE_ENCODE)
        val saved = store.load(lecture.id) ?: lecture
        store.savePower(saved, report)
        store.appendPowerLog(com.chencohensolution.transcriptor.recording.PowerLogCsv.row(lecture.id, lecture.labels.joinToString("+"), lecture.startedAtMillis, report))
    }

    /** The battery, 0..100, and whether the phone is on the charger. */
    fun battery(context: android.content.Context): Pair<Int, Boolean> {
        val manager = context.getSystemService(android.os.BatteryManager::class.java)
        return manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) to manager.isCharging
    }

    /**
     * Encodes the lectures waiting for FLAC or AAC, one after another, when the setting allows it now (right after Stop,
     * or on the charger above 30%); [progress] gets each one's string and 0..1. False when none ran (none waiting, one
     * already running, or the setting waits for the charger, which then gets an [EncodeJob]). [now]: whatever the
     * setting and the charger (Upload now, which the user tapped).
     */
    fun encodePending(context: android.content.Context, now: Boolean = false, progress: (Lecture, Float) -> Unit = { _, _ -> }): Boolean {
        val waiting = compression.pending()
        if (waiting.isEmpty()) return false
        val (percent, charging) = battery(context)
        // Each moment by its profile's mode: one saving battery waits for the charger (Music has its own mode).
        val pending = if (now) waiting else waiting.filter { settings.encodeAllowed(it, charging, percent) }
        if (pending.size < waiting.size) EncodeJob.schedule(context)
        if (pending.isEmpty()) return false
        if (!encoding.compareAndSet(false, true)) return false
        try {
            for (lecture in pending) {
                if (session.isRecording) { EncodeJob.schedule(context); break }
                // Metered like the recording ("encode" in power-log.csv), so WAV-then-encode can be weighed against encoding live.
                val meter = com.chencohensolution.transcriptor.recording.PowerMeter(powerProbe, intervalMillis = 5_000)
                val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
                meter.start(scope)
                val began = System.currentTimeMillis()
                val toAac = lecture.pendingAac
                runCatching { compression.compress(lecture) { p -> compressing.value = lecture.id to p; progress(lecture, p) } }
                    .onSuccess { done -> if (lastLecture.value?.id == done.id) lastLecture.value = done }
                    .also {
                        val samples = meter.stop()
                        scope.cancel()
                        if (it.isSuccess) runCatching { logEncode(lecture, toAac, samples, System.currentTimeMillis() - began) }
                    }
                    .onFailure { android.util.Log.e("Recording", "encoding ${lecture.id} failed", it) }
            }
        } finally {
            compressing.value = null
            encoding.set(false)
        }
        return true
    }

    /** The speaker pass: the lecture it runs on and 0..1, null when not running. */
    val findingSpeakers = MutableStateFlow<Pair<String, Float>?>(null)

    /** The passes running, by lecture: what the Notebooks screen shows under a lecture. */
    val working = combine(compressing, findingSpeakers, refining) { c, f, r -> listOfNotNull(c, f, r).toMap() }

    lateinit var labels: LabelStore
        private set

    /** The labels and the moments, for the first screen. */
    lateinit var library: NotebooksController
        private set

    /** The phone's first language the app can transcribe ("he" on a Hebrew phone), else English. */
    fun phoneLanguage(): String {
        val locales = android.os.LocaleList.getDefault()
        return Languages.default((0 until locales.size()).map { locales[it].language })
    }

    /** The phone's offset from UTC at a time: weekdays and hours for the label guess and the transcript's date. */
    val utcOffset = { at: Long -> java.util.TimeZone.getDefault().getOffset(at).toLong() }


    /** The models a lecture in [language] can be improved with: the downloaded ones, large Whisper first, the Zipformer last. */
    fun refineModels(language: String): List<SpeechModel> =
        ModelChoices.candidates(modelLibrary.forLanguage(language, SherpaNpuWhisperEngine.chip), ModelJob.Improve, models::isReady).filter(models::isReady)

    /** The model that improves [language]: the one chosen in Settings if it is downloaded, else the first that is. */
    fun improveModelFor(language: String): SpeechModel? = ModelChoices.running(refineModels(language), settings.refineModel(language), models::isReady)

    /** With nothing downloaded to improve [language] with: the model to get. */
    fun improveSuggestion(language: String): SpeechModel? =
        ModelChoices.improveSuggestion(modelLibrary.forLanguage(language, SherpaNpuWhisperEngine.chip), models::isReady)

    lateinit var installedModels: InstalledModels
        private set

    /** Improve passes a recording stopped (moment id, model id), to start again once it stops; in memory only. */
    val improveAfterRecording: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

    /** Loads NPU models so one too big for the phone is refused, or disabled after it got the app killed. */
    lateinit var npuGuard: NpuLoadGuard
        private set

    /** [make]s a streaming engine, inside [npuGuard] when [model] runs on the NPU. */
    fun <T> npuEngine(model: SpeechModel, make: () -> T): T =
        if (model.kind.onNpu) npuGuard.load(model, make) else make()

    /** whisper.cpp with [model]'s `model.bin`; for [ModelKind.WhisperNpuEncoder], its encoder on the NPU (`encoder.bin`). */
    fun whisperEngine(model: SpeechModel): WhisperEngine {
        val model0 = model.files.first { it.name == "model.bin" }
        return if (model.kind == ModelKind.WhisperNpuEncoder) {
            WhisperEngine(models.file(model, model0).path, File(models.dir(model), "encoder.bin"), nativeLibDir)
        } else {
            WhisperEngine(models.file(model, model0).path)
        }
    }

    /** The catalog's models and those added from Hugging Face. */
    lateinit var modelLibrary: ModelLibrary
        private set

    /** Hugging Face's API, read over HTTPS (not on the main thread). */
    val hub = HubClient { url ->
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (connection.responseCode !in 200..299) throw java.io.IOException("Hugging Face answered ${connection.responseCode}")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Tries a downloaded NPU model on the NPU once (files: not on the main thread): loaded, with a second of silence
     * through it. Qualcomm's runtime refuses a build for another chip or runtime version at once. Kept in models.json.
     */
    fun checkNpu(model: SpeechModel): NpuResult {
        val result = runCatching {
            val dir = models.dir(model)
            when (model.kind) {
                ModelKind.WhisperNpu -> SherpaNpuWhisperEngine(dir, nativeLibDir).use { engine ->
                    val language = model.languages.firstOrNull { it != SpeechModel.MULTILINGUAL } ?: "en"
                    npuGuard.load(model) {
                        engine.load(language)
                        engine.transcribe(FloatArray(16_000), language, null)
                    }
                }
                ModelKind.WhisperNpuEncoder -> npuEngine(model) { whisperEngine(model) }.use { engine ->
                    val language = model.languages.firstOrNull { it != SpeechModel.MULTILINGUAL } ?: "en"
                    engine.transcribe(FloatArray(16_000), language, null)
                }
                ModelKind.StreamingTransducerNpu -> npuEngine(model) { SherpaStreamingEngine(dir, nativeLibDir) }.use { engine ->
                    engine.accept(FloatArray(16_000))
                    engine.text
                }
                else -> Unit
            }
        }
        val npu = result.fold({ NpuResult(runs = true) }, { NpuResult(runs = false, reason = it.message ?: it.toString()) })
        // Refused for want of free memory now: not a verdict on the model, so not kept.
        if ((result.exceptionOrNull() as? NpuLoadGuard.Refused)?.now != true) installedModels.setNpu(model.id, npu)
        return npu
    }

    /**
     * The refine pass on [lecture] with [modelId] (minutes to hours with a large model; not on the main thread): the
     * current transcript kept as a version first, then every line transcribed again, edited lines kept. Saves and
     * returns it. [kept] is called once the version is kept, so an open review screen can read it. [stop] true ends it
     * early ([RefinePass.Stopped]), its model closed, nothing written.
     */
    fun refine(lecture: Lecture, modelId: String, kept: () -> Unit = {}, progress: (Float) -> Unit = {}, stop: () -> Boolean = { false }): Lecture {
        val model = refineModels(lecture.language).firstOrNull { it.id == modelId } ?: error("$modelId isn't downloaded")
        store.repairAudio(lecture)
        // Only recorded so far (After on the Record bar): its lines are found and written, nothing to keep or merge.
        if ((store.load(lecture.id) ?: lecture).let { it.pendingTranscript && it.segments.isEmpty() }) return transcribe(lecture, model, progress, stop)
        val withVersion = store.keepCurrent(store.load(lecture.id) ?: lecture, System.currentTimeMillis())
        store.save(withVersion)
        kept()
        val path = store.audioPath(lecture)
        val audio = { a: Long, b: Long -> RecordingAudio.range({ openAudio(path) }, lecture.audioFile == AUDIO_WAV, a, b) }
        val started = System.currentTimeMillis()
        val done = withRefinePass(model, lecture.language, audio) { it.run(withVersion, model.id, progress, stop) }
        // Its speed on this phone, for the next estimate.
        settings.recordImproveSpeed(model.id, System.currentTimeMillis() - started, lecture.durationMillis)
        // Saved over what is on disk now, with its edits since (the review screen may have changed it meanwhile):
        // only the lines' texts the pass wrote are taken.
        val now = store.load(lecture.id) ?: withVersion
        val refined = done.segments.associateBy { it.id }
        val merged = now.copy(
            segments = now.segments.map { s -> refined[s.id]?.takeIf { !s.edited && it.modelId == model.id }?.let { s.copy(text = it.text, modelId = it.modelId, words = emptyList()) } ?: s },
            refinedWith = model.id,
        )
        store.save(merged)
        return merged
    }

    /** [use]s a [RefinePass] over [audio] with [model] loaded (on the NPU inside [npuGuard]), closing it after. */
    private fun <T> withRefinePass(model: SpeechModel, language: String, audio: (Long, Long) -> FloatArray, use: (RefinePass) -> T): T = when (model.kind) {
        ModelKind.Whisper, ModelKind.WhisperNpuEncoder -> npuEngine(model) { whisperEngine(model) }.use { engine -> use(RefinePass(engine, language, audio)) }
        ModelKind.WhisperNpu -> SherpaNpuWhisperEngine(models.dir(model), nativeLibDir).use { engine ->
            npuGuard.load(model) { engine.load(language) }
            use(RefinePass(engine, language, audio))
        }
        else -> npuEngine(model) { SherpaStreamingEngine(models.dir(model), nativeLibDir.takeIf { model.kind == ModelKind.StreamingTransducerNpu }) }.use { engine ->
            use(RefinePass({ samples -> transcribeLineStreaming(engine, samples) }, audio))
        }
    }

    /**
     * Transcribes a moment that was only recorded ([Lecture.pendingTranscript]) with [model] (not on the main thread):
     * its lines are found at the pauses of the whole recording ([lineTimes], the first tenth of [progress]), then
     * written by the model as the improve pass writes them, several lines together where it can. Saved over what is on
     * disk now (notes added since are kept) and returned. [stop] true ends it early ([RefinePass.Stopped]), nothing
     * written.
     */
    private fun transcribe(lecture: Lecture, model: SpeechModel, progress: (Float) -> Unit, stop: () -> Boolean): Lecture {
        val path = store.audioPath(lecture)
        val isWav = lecture.audioFile == AUDIO_WAV
        val length = lecture.durationMillis.coerceAtLeast(1)
        val lines = RecordingAudio(openAudio(path), isWav).use { audio ->
            lineTimes(audio, progress = { at -> progress((at.toFloat() / length).coerceAtMost(1f) * LINES_SHARE) }, stop = stop)
        }
        if (stop()) throw RefinePass.Stopped()
        val audio = { a: Long, b: Long -> RecordingAudio.range({ openAudio(path) }, isWav, a, b) }
        val started = System.currentTimeMillis()
        val done = transcribed(lecture, lines, model.id) { withLines ->
            if (withLines.segments.isEmpty()) withLines
            else withRefinePass(model, lecture.language, audio) { it.run(withLines, model.id, { p -> progress(LINES_SHARE + p * (1 - LINES_SHARE)) }, stop) }
        }
        if (lines.isNotEmpty()) settings.recordImproveSpeed(model.id, System.currentTimeMillis() - started, lecture.durationMillis)
        val now = store.load(lecture.id) ?: lecture
        val saved = now.copy(segments = done.segments, refinedWith = done.refinedWith, pendingTranscript = false)
        store.save(saved)
        return saved
    }

    /** How much of the transcribe pass's progress finding the lines is; the rest is writing them. */
    private const val LINES_SHARE = 0.1f

    lateinit var voices: VoiceStore
        private set

    /** The lecture opened after recording (the review screen). */
    lateinit var review: LectureReview
        private set

    /**
     * A speaker's voice print in [lecture]: up to 20 s of their lines, longest first; null without the voice-print
     * model or enough of their speech. Reads the recording: not on the main thread for long lectures.
     */
    fun printOf(lecture: Lecture, speakerId: String): List<Float>? {
        val model = voiceModel ?: return null
        val path = store.audioPath(lecture)
        val audio = com.chencohensolution.transcriptor.speech.FloatBuilder()
        for (line in lecture.segments.filter { it.speakerId == speakerId }.sortedByDescending { it.endMillis - it.startMillis }) {
            if (audio.size >= 20 * 16_000) break
            audio.add(RecordingAudio.range({ openAudio(path) }, lecture.audioFile == AUDIO_WAV, line.startMillis, line.endMillis))
        }
        if (audio.size < SpeakerLabeler.MIN_SAMPLES) return null
        val embedder = voiceEmbedder(model)
        return try { embedder.embed(audio.toArray()).toList() } finally { embedder.close() }
    }

    /**
     * The voice-print model for speaker labels: the one picked in Settings › Models if it is on the phone, else the
     * catalog's (CAM++) if downloaded; null without one. Read when a lecture or a speaker pass starts.
     */
    val voiceModel: SpeechModel?
        get() = (ModelCatalog.voicePrintModels + addedSpeakerModels(ModelKind.SpeakerEmbedding)).let { all ->
            all.firstOrNull { it.id == settings.voiceModel && models.isReady(it) } ?: all.first().takeIf { models.isReady(it) }
        }

    /**
     * The segmentation model for live speaker turns: the one picked in Settings › Models if it is on the phone, else
     * pyannote's if downloaded; null without one (speakers are then labeled by each line's print).
     */
    val segmentationModel: SpeechModel?
        get() = (ModelCatalog.segmentationModels + addedSpeakerModels(ModelKind.SpeakerSegmentation)).let { all ->
            all.firstOrNull { it.id == settings.segmentationModel && models.isReady(it) } ?: all.first().takeIf { models.isReady(it) }
        }

    /** The speaker models of [kind] added from Hugging Face (Settings › Speaker recognition or turns › Find more). */
    private fun addedSpeakerModels(kind: ModelKind) = installedModels.all().map { it.model }.filter { it.kind == kind }

    /** [model]'s files: its ONNX, and its NPU build on the phone it was compiled for. */
    fun voicePrintFiles(model: SpeechModel) = VoicePrintFiles.of(models.dir(model), SherpaNpuWhisperEngine.chip)

    /** [model]'s voice prints, on the NPU when it has a build for this phone (else, or if it fails, on the CPU). */
    fun voiceEmbedder(model: SpeechModel) = SherpaVoiceEmbedder(voicePrintFiles(model), nativeLibDir)

    /** Both models of the speaker pass are on the phone. */
    val canFindSpeakers get() = models.isReady(ModelCatalog.speakerSegmentation) && voiceModel != null

    /**
     * Runs the speaker pass on [lecture] (seconds to minutes; not on the main thread): who spoke when, the lines
     * relabeled and split to match. Speakers keep no names from other moments. Saves and returns it.
     */
    fun findSpeakers(lecture: Lecture, progress: (Float) -> Unit = {}): Lecture {
        store.repairAudio(lecture)
        val model = checkNotNull(voiceModel) { "no voice-print model" }
        val thresholds = VoicePrintThresholds.of(model.id, settings.speakerTuning(model.id))
        val diarizer = SherpaDiarizer(models.file(ModelCatalog.speakerSegmentation), voicePrintFiles(model), thresholds.clustering, nativeLibDir)
        val embedder = voiceEmbedder(model)
        try {
            RecordingAudio(openAudio(store.audioPath(lecture)), lecture.audioFile == AUDIO_WAV).use { audio ->
                // Speakers aren't guessed from other moments (see LectureSession.start): the only known voices are
                // the ones the user named in this very moment, so running the pass again keeps their names.
                val here = lecture.speakers.map { it.name }.filter { it.isNotEmpty() }.toSet()
                val result = SpeakerPass(diarizer, embedder, model.id, thresholds.sameSpeaker).run(audio, voices.load().filter { it.name in here }) { at ->
                    progress((at.toFloat() / lecture.durationMillis.coerceAtLeast(1)).coerceAtMost(1f))
                }
                val done = lecture.withTurns(result.turns, result.speakers)
                store.save(done)
                return done
            }
        } finally {
            diarizer.close()
            embedder.close()
        }
    }

    /** Deletes lectures for good (files: not on the main thread), except those recording, open or worked on now. */
    suspend fun delete(ids: List<String>) {
        val lectureIds = momentsHere(ids)
        val busy = busy()
        backup.dismiss(lectureIds.filter { it !in busy })
        library.delete(lectureIds) { it in busy }
    }

    /**
     * Adds and removes labels on moments (files: not on the main thread), except those recording or worked on now
     * (an open one can't be on the list's screen).
     */
    suspend fun setLabels(ids: List<String>, add: Set<String>, remove: Set<String>) {
        val lectureIds = momentsHere(ids)
        val busy = busy()
        library.setLabels(lectureIds, add, remove) { it in busy }
    }

    /**
     * Tucks moments away, or brings them back (files and network: not on the main thread), except those recording or
     * worked on now; the server is told for those backed up.
     */
    suspend fun setTucked(ids: List<String>, tucked: Boolean) {
        val lectureIds = momentsHere(ids)
        val busy = busy()
        val changed = library.setTucked(lectureIds, tucked) { it in busy }
        backup.setTucked(changed, tucked)
    }

    /**
     * Marks moments private: each is kept on the server, then deleted from the phone ([ServerBackup.makePrivate]).
     * Returns how many left the phone, and why the first that didn't stayed. Not on the main thread.
     */
    suspend fun makePrivate(ids: List<String>): Pair<Int, PrivateResult?> {
        val lectureIds = momentsHere(ids)
        var done = 0
        var failed: PrivateResult? = null
        for (id in lectureIds) {
            when (val result = backup.makePrivate(id, busy())) {
                PrivateResult.Done -> done++
                else -> if (failed == null) failed = result
            }
        }
        library.reload()
        return done to failed
    }

    /** Deletes a label; its moments stay, without it. Files: not on the main thread. */
    fun deleteLabel(labelId: String) {
        val busy = busy()
        library.deleteLabel(labelId) { it in busy }
    }

    private lateinit var resolver: ContentResolver

    /** The lectures folder outside the app: none picked yet, ready, moving lectures into it, or no longer reachable. */
    sealed interface Folder {
        data object NotSet : Folder
        data class Ready(val name: String) : Folder
        data class Moving(val name: String, val progress: Float) : Folder
        data class Lost(val name: String) : Folder
    }

    val folder = MutableStateFlow<Folder>(Folder.NotSet)

    /** A recording's file, in the app's storage or in the lectures folder, opened for reading (with seeks). */
    fun openAudio(path: Path): ParcelFileDescriptor {
        val library = store.library?.fileSystem as? SafFileSystem
        return if (library != null && path.toString().startsWith("$LIBRARY_ROOT/")) library.openFd(path)
        else ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The folder in the settings, if the app may still use it: the lectures' library from now on. */
    private fun connectLibrary() {
        val saved = settings.lectureFolder ?: return
        val uri = renamedLibrary(Uri.parse(saved))
        val fs = SafFileSystem(resolver, uri)
        val name = runCatching { fs.name }.getOrDefault("")
        if (!canUse(uri) || name.isEmpty()) {
            folder.value = Folder.Lost(name.ifEmpty { uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "" })
            return
        }
        store.library = Place(fs, LIBRARY_ROOT)
        folder.value = Folder.Ready(name)
    }

    /**
     * A moments folder still named Transcriptor becomes "Audio Logs" (the app's name since Nov 7, 2026; owner's
     * request, Nov 15): renamed in place by the documents provider, with everything in it, and the settings point at
     * it under its new name. Only when the folder sits inside the one the user picked: the picked folder itself can't
     * be renamed without losing the permission it was picked with, so that one keeps its name. Any failure (a folder
     * named Audio Logs is already there, the provider refuses) leaves it as it was.
     */
    private fun renamedLibrary(uri: Uri): Uri {
        val inside = runCatching { DocumentsContract.getDocumentId(uri) != DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(false)
        if (!inside || !canUse(uri)) return uri
        val name = runCatching { SafFileSystem(resolver, uri).name }.getOrDefault("")
        if (name != OLD_LIBRARY_FOLDER) return uri
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, uri, LIBRARY_FOLDER) }
            .onFailure { android.util.Log.w("Recording", "the moments folder keeps its old name", it) }.getOrNull() ?: return uri
        settings.lectureFolder = renamed.toString()
        android.util.Log.i("Recording", "the moments folder is now $LIBRARY_FOLDER")
        return renamed
    }

    /** The app still holds the read and write permission the folder's tree was picked with. */
    private fun canUse(folderUri: Uri): Boolean {
        val tree = DocumentsContract.buildTreeDocumentUri(folderUri.authority, DocumentsContract.getTreeDocumentId(folderUri))
        return resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    }

    /**
     * Makes the folder the user picked ([tree], from the system's folder picker) the lectures folder (files: not on the
     * main thread): an `Audio Logs` folder in it unless it is one already (by name, or a library from before), an
     * empty `.nomedia` so music and gallery apps skip the recordings, then the labels, voices and finished moments
     * move in ([adoptLibrary]), from the app or from the folder used before, whose permission is then given back.
     */
    fun chooseFolder(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(tree, flags)
        val treeFs = SafFileSystem(resolver, tree)
        val isLibrary = treeFs.name == LIBRARY_FOLDER || treeFs.name == OLD_LIBRARY_FOLDER || treeFs.exists(LIBRARY_ROOT / LabelStore.FILE) || treeFs.exists(LIBRARY_ROOT / NOTEBOOKS_FILE)
        val folderUri = if (isLibrary) {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } else {
            // A library from before the rename, in the folder picked: used (and renamed), not left beside a new one.
            val old = LIBRARY_ROOT / OLD_LIBRARY_FOLDER
            if (!treeFs.exists(LIBRARY_ROOT / LIBRARY_FOLDER) && treeFs.exists(old)) {
                renamedLibrary(DocumentsContract.buildDocumentUriUsingTree(tree, treeFs.documentId(old)))
            } else {
                treeFs.createDirectories(LIBRARY_ROOT / LIBRARY_FOLDER)
                DocumentsContract.buildDocumentUriUsingTree(tree, treeFs.documentId(LIBRARY_ROOT / LIBRARY_FOLDER))
            }
        }
        val fs = SafFileSystem(resolver, folderUri)
        if (!fs.exists(LIBRARY_ROOT / ".nomedia")) fs.write(LIBRARY_ROOT / ".nomedia") {}
        val before = settings.lectureFolder
        val target = Place(fs, LIBRARY_ROOT)
        folder.value = Folder.Moving(fs.name, 0f)
        try {
            adoptLibrary(store, labels, target, busy(), System.currentTimeMillis(), utcOffset) { folder.value = Folder.Moving(fs.name, it) }
            settings.lectureFolder = folderUri.toString()
            folder.value = Folder.Ready(fs.name)
            before?.takeIf { it != folderUri.toString() }?.let { old ->
                val oldUri = Uri.parse(old)
                val oldTree = DocumentsContract.buildTreeDocumentUri(oldUri.authority, DocumentsContract.getTreeDocumentId(oldUri))
                if (oldTree != DocumentsContract.buildTreeDocumentUri(tree.authority, DocumentsContract.getTreeDocumentId(tree))) {
                    runCatching { resolver.releasePersistableUriPermission(oldTree, flags) }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Recording", "moving to the lectures folder failed", e)
            folder.value = before?.let { Folder.Ready((store.library?.fileSystem as? SafFileSystem)?.name ?: "") } ?: Folder.NotSet
            throw e
        } finally {
            library.reload()
        }
    }

    /** The lectures that mustn't move now: being recorded, open, or with a pass running on them. */
    private fun busy(): Set<String> = setOfNotNull(session.state.value?.lecture?.id, review.lecture?.id) +
        listOfNotNull(compressing.value, findingSpeakers.value, refining.value).map { it.first }

    /**
     * The lectures folder from before labels moves to labels ([migrateToLabels]), once per start; before the first
     * list. Files: not on the main thread.
     */
    fun migrateLibrary() {
        if (libraryMigrated) return
        libraryMigrated = true
        val library = store.library ?: return
        runCatching { migrateToLabels(library, System.currentTimeMillis(), utcOffset) }.onFailure { android.util.Log.e("Recording", "label migration in the lectures folder failed", it) }
    }

    @Volatile private var libraryMigrated = false

    /** The finished lectures still in the app move to the lectures folder (files: not on the main thread). */
    fun archive() {
        if (folder.value !is Folder.Ready) return
        if (archiveFinished(store) { it in busy() } > 0) library.reload()
    }

    /** Backing up moments to the journal server through shed; off until the user pairs (Settings › Server backup). */
    lateinit var backup: ServerBackup
    lateinit var shed: AndroidShed

    /**
     * Hands the finished moments not backed up yet to shed and starts the upload work if anything waits (files: not
     * on the main thread). Nothing happens while the app isn't paired with a server.
     */
    suspend fun backUp(context: android.content.Context) {
        // What the background work sent while the app was closed, recorded and acknowledged.
        runCatching { backup.recordUploads() }.onFailure { android.util.Log.e("Recording", "recording uploads failed", it) }
        runCatching { backup.backUpFinished(busy()) }.onFailure { android.util.Log.e("Recording", "backing up failed", it) }
        if (backup.waiting() > 0) scheduleUploads(context)
        // The calls folder, in the app's own scope: the first time it hashes every call there.
        if (backup.callsFolder != null) syncScope.launch { backUpCalls(context) }
        // The newest page of the server's recordings, the labels, and the moments' words to the server: at most once a minute.
        val now = System.currentTimeMillis()
        // In the app's own scope: leaving a screen must not cut a sync off halfway (notes half sent, a delete half done).
        if (backup.state.value.paired != null && now - lastServerSync > 60_000) {
            lastServerSync = now
            syncScope.launch { syncWithServer() }
        }
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Back up now, forced: the finished moments and the new calls handed over, then the queue sent here and now on the
     * network there is, not left to the background work (which may be waiting out a retry, or for Wi-Fi). Then the
     * background work is scheduled again for what is left.
     */
    fun uploadNow(context: android.content.Context) {
        if (backup.state.value.uploading) return
        backup.markUploading(true)
        syncScope.launch {
            try {
                // What already waits goes first: reading the calls folder for new calls can take a while.
                android.util.Log.i("Recording", "upload now: ${backup.waiting()} waiting")
                runCatching { backup.runUploads() }.onSuccess { android.util.Log.i("Recording", "upload now: $it") }
                    .onFailure { android.util.Log.e("Recording", "uploading failed", it) }
                // Moments still WAV, waiting for the charger to be compressed, are compressed now and then sent (hen,
                // Nov 17, 2026: Upload now sends everything).
                if (!session.isRecording && compression.pending().isNotEmpty()) {
                    backup.markCompressing(compression.pending().size)
                    runCatching { encodePending(context, now = true) }.onFailure { android.util.Log.e("Recording", "compressing failed", it) }
                    backup.markCompressing(0)
                }
                runCatching { backup.backUpFinished(busy()) }.onFailure { android.util.Log.e("Recording", "backing up failed", it) }
                if (backup.callsFolder != null) backUpCalls(context)
                if (backup.waiting() > 0) runCatching { backup.runUploads() }.onSuccess { android.util.Log.i("Recording", "upload now, new: $it") }
                    .onFailure { android.util.Log.e("Recording", "uploading failed", it) }
            } finally {
                backup.markUploading(false)
            }
            // What was handed over before uploads were recorded, asked of the server (cheap: by size).
            runCatching { backup.confirmWithServer() }.onFailure { android.util.Log.w("Recording", "confirming uploads", it) }
            if (backup.waiting() > 0) scheduleUploads(context)
            refreshServerMoments()
        }
    }

    /** The phone's network now is metered (mobile data, a hotspot): Back up now asks first when Wi-Fi only is on. */
    fun onMeteredNetwork(context: android.content.Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork != null && cm.isActiveNetworkMetered
    }
    private val callsRunning = kotlinx.coroutines.sync.Mutex()

    /**
     * Hands shed the new calls in the calls folder and schedules their sending (files: not on the main thread). A pass
     * already running makes it return at once.
     */
    suspend fun backUpCalls(context: android.content.Context) {
        if (!callsRunning.tryLock()) return
        try {
            if (backup.callsFolder != null) openCallsFolder()
            runCatching { backup.backUpCalls(System.currentTimeMillis()) }.onFailure { android.util.Log.e("Recording", "backing up calls failed", it) }
            if (backup.waiting() > 0) scheduleUploads(context)
        } finally {
            callsRunning.unlock()
        }
    }

    /** The calls folder kept, read through its picked tree while the app still may; else none until picked again. */
    private fun openCallsFolder() {
        val folder = backup.callsFolder?.let(Uri::parse)
        val allowed = folder != null && resolver.persistedUriPermissions.any { it.uri == folder && it.isReadPermission }
        backup.useCallsFolder(if (allowed) SafFileSystem(resolver, folder!!) else null, LIBRARY_ROOT)
    }

    /** Backs up the calls in the folder the user picked ([tree], from the system's folder picker), reading it only. */
    fun chooseCallsFolder(context: android.content.Context, tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        releaseCallsFolder(except = tree)
        backup.chooseCallsFolder(tree.toString(), runCatching { SafFileSystem(resolver, tree).name }.getOrNull())
        openCallsFolder()
        syncScope.launch { backUpCalls(context) }
    }

    /** Stops backing up calls; what was sent stays on the server, and the phone keeps its calls. */
    fun stopCalls() {
        releaseCallsFolder(except = null)
        backup.chooseCallsFolder(null, null)
    }

    /** Gives back the read permission of the calls folder kept, unless it is [except] or the lectures folder's tree. */
    private fun releaseCallsFolder(except: Uri?) {
        val old = backup.callsFolder?.let(Uri::parse) ?: return
        if (old == except) return
        val lectures = settings.lectureFolder?.let(Uri::parse)?.let { DocumentsContract.buildTreeDocumentUri(it.authority, DocumentsContract.getTreeDocumentId(it)) }
        if (old == lectures) return
        runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    private val syncing = kotlinx.coroutines.sync.Mutex()

    @Volatile private var lastServerSync = 0L

    @Volatile private var indexChecked = false

    /**
     * Once a start, after the list showed from the index: the index made again from every moment's folder (slow in the
     * lectures folder), so what changed outside the app shows too. Files: not on the main thread.
     */
    fun checkIndexOnce() {
        if (indexChecked) return
        indexChecked = true
        runCatching { store.rescan() }.onFailure { android.util.Log.w("Recording", "reading the moments again", it) }
        library.reload()
    }

    /** The server's recordings, the labels and the moments' words brought up to date now (after Calls was switched, or Back up now). */
    suspend fun refreshServerMoments() = syncWithServer()

    private suspend fun syncWithServer() = syncing.withLock {
        lastServerSync = System.currentTimeMillis()
        runCatching {
            backup.tidyServerMoments(busy())
            backup.forgetBrokenMoments(busy())
            backup.syncServerList()
            if (backup.syncLabels(labels, System.currentTimeMillis())) library.reload()
            backup.syncNotes(busy())
        }.onFailure { android.util.Log.w("Recording", "syncing with the server", it) }
        // What was handed over before uploads were recorded: asked of the server once each.
        runCatching { backup.confirmWithServer() }.onFailure { android.util.Log.w("Recording", "confirming uploads", it) }
        library.reload()
    }

    /** The server's recordings the list holds, by the id their moment has: to make one a moment here when it is opened. */
    @Volatile private var serverById: Map<String, com.chencohensolution.shed.RemoteFile> = emptyMap()

    @Volatile private var summaryCache: Map<com.chencohensolution.shed.RemoteFile, Pair<String, com.chencohensolution.transcriptor.notebook.LectureSummary>> = emptyMap()

    /** The list's server recordings as moments, each time the server's list changes. */
    private fun showServerMoments(window: com.chencohensolution.transcriptor.backup.ServerWindow) {
        // Moments made earlier from these recordings take the source they have now (calls filed as recordings).
        if (runCatching { backup.takeSources(window.files) }.getOrDefault(false)) library.reload()
        val language = library.library.value.lastLanguage
        val shown = window.files.filter { !backup.isMomentHere(it.sha256) && !backup.isDismissed(it.sha256) }
        // Made again only for a recording that changed: the list may hold thousands.
        val before = summaryCache
        val summaries = shown.map { f -> f to (before[f]?.takeIf { it.first == language }?.second ?: backup.summaryOf(f, language)) }
        summaryCache = summaries.associate { (f, s) -> f to (language to s) }
        serverById = summaries.associate { (file, s) -> s.id to file }
        library.setServer(com.chencohensolution.transcriptor.notebook.ServerPart(summaries.map { it.second }, window.older, window.newer, window.loading))
    }

    /**
     * Makes the server's recordings among [lectureIds] moments here (before they are opened or changed): each from its
     * note, its audio left on the server. Files and network: not on the main thread.
     */
    suspend fun momentsHere(lectureIds: Collection<String>): List<String> {
        var made = false
        val ids = lectureIds.map { id ->
            if (store.load(id) != null) return@map id
            val file = serverById[id]
                ?: backup.serverMoments.window.value.files.firstOrNull { "server-" + it.sha256.take(16) == id }
                ?: return@map id.also { android.util.Log.w("Recording", "$id: not a moment here, nor in the server's list") }
            // The moment the recording became: a moment here may already have it under another id.
            runCatching { backup.momentHere(file, library.library.value.lastLanguage) }
                .onSuccess { made = true }
                .onFailure { android.util.Log.w("Recording", "making $id a moment", it) }
                .getOrNull()?.id ?: id
        }
        if (made) library.reload()
        return ids
    }

    /** Puts a label's moments behind the phone's lock, or takes them out. */
    fun setLabelLocked(id: String, locked: Boolean) = library.setLabelLocked(id, locked)

    /** Whether calls and voice messages are behind the phone's lock (Settings › Server backup). */
    fun locks() = com.chencohensolution.transcriptor.notebook.Locks(settings.lockCalls, settings.lockVoiceMessages)

    /** Puts calls or voice messages behind the phone's lock, or takes them out. Files: not on the main thread. */
    fun setLocks(locks: com.chencohensolution.transcriptor.notebook.Locks) {
        settings.lockCalls = locks.calls
        settings.lockVoiceMessages = locks.voiceMessages
        library.locksChanged()
    }

    /** The moment's whole audio on the phone, brought from the server when it is there only: before a pass reads it. */
    suspend fun audioHere(lecture: com.chencohensolution.transcriptor.notebook.Lecture) {
        if (!store.hasAudio(lecture)) backup.bringAudio(lecture.id)
    }

    /** Free up space: removes the audio of [ids] the server holds (files and network: not on the main thread). */
    suspend fun freeUp(ids: List<String>, progress: (Int, Int) -> Unit) =
        backup.freeUp(ids, busy(), progress).also { library.reload() }

    fun freeUpPlan(rule: com.chencohensolution.transcriptor.backup.FreeUpRule) = backup.freeUpPlan(rule, System.currentTimeMillis(), busy())

    fun scheduleUploads(context: android.content.Context) = ShedUploads.schedule(context, backup.wifiOnly)

    fun init(app: Application) {
        val root = app.filesDir.toOkioPath()
        store = LectureStore(FileSystem.SYSTEM, root, utcOffset)
        backup = ServerBackup(store, FileSystem.SYSTEM, root, utcOffset)
        shed = AndroidShed(app, "audio-logs", AndroidShed.AUDIO_ONLY, backup.locator, listedKinds = setOf(com.chencohensolution.shed.FileKind.AUDIO))
        backup.attach(shed.shed, shed.journal)
        store.onSaved = { id -> runCatching { backup.momentSaved(id) } }
        // The resolver first: the calls folder is read through it.
        resolver = app.contentResolver
        openCallsFolder()
        CallsWorker.schedule(app)
        settings = SettingsStore(FileSystem.SYSTEM, root)
        installedModels = InstalledModels(FileSystem.SYSTEM, root)
        npuGuard = NpuLoadGuard(installedModels, log = { android.util.Log.i("NpuLoadGuard", it) }) {
            val info = android.app.ActivityManager.MemoryInfo()
            app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(info)
            info.availMem
        }
        // An NPU model whose load got the app killed last time: disabled for this phone, the pick falls back.
        npuGuard.recover()?.let { android.util.Log.w("Transcriptor", "NPU model $it disabled: the app was killed loading it") }
        modelLibrary = ModelLibrary(installedModels)
        labels = LabelStore { store.library ?: store.local }
        resolver = app.contentResolver
        connectLibrary()
        // Notebooks became labels: the app's own storage moves now (quick: one folder rename per moment); the
        // lectures folder with the first list ([migrateLibrary]), off the main thread.
        runCatching { migrateToLabels(store.local, System.currentTimeMillis(), utcOffset, into = store.library ?: store.local) }.onFailure { android.util.Log.e("Recording", "label migration failed", it) }
        library = NotebooksController(labels, store, System::currentTimeMillis, ::phoneLanguage, ::locks,
            onServer = { if (backup.state.value.paired == null) null else { id -> backup.stateOf(id) is com.chencohensolution.transcriptor.backup.MomentBackup.BackedUp } },
            shown = {
                val state = backup.state.value
                val kinds = state.shown
                com.chencohensolution.transcriptor.notebook.Shown(kinds?.calls ?: true, kinds?.voiceMessages ?: true, server = state.paired == null || state.showServer)
            })
        // A backup done, or the pairing changed: the marks again.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { backup.state.collect { runCatching { library.locksChanged() } } }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            backup.serverMoments.window.collect { runCatching { showServerMoments(it) } }
        }
        models = ModelFiles(app)
        nativeLibDir = app.applicationInfo.nativeLibraryDir
        session = LectureSession(
            AndroidRecorder(inputs = com.chencohensolution.transcriptor.recording.AndroidAudioInputs(app).also { audioInputs = it }), store, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            voices = VoiceStore({ voiceModel?.id ?: VoiceStore.DEFAULT_MODEL }) { store.library ?: store.local }.also { voices = it },
            live = ::liveModel,
            liveAvailable = { liveModelFor(it) != null },
            speakerPassReady = { canFindSpeakers },
            power = PowerMeter(AndroidPowerProbe(app).also { powerProbe = it }),
        )
        compression = AudioCompression(
            FileSystem.SYSTEM, store, androidFlacEncode, { settings.profileFor(it).flacLevel }, androidAacEncode,
            { settings.profileFor(it).aac }, { settings.profileFor(it).flac },
            com.chencohensolution.transcriptor.recording.androidOtherEncode, { key, codec -> settings.profileFor(key).other(codec) },
        )
        review = LectureReview(
            store, voices, AndroidAudioPlayer(::openAudio), CoroutineScope(SupervisorJob() + Dispatchers.Main), ::printOf,
            speakerTuning = settings::speakerTuning, setSpeakerTuning = settings::setSpeakerTuning,
            streamOf = backup::streamOf,
        )
    }
}

/** The live models for [language] that are downloaded, the language's own first (ivrit.ai's for Hebrew). */
fun readyLiveModels(language: String) =
    Recording.modelLibrary.forLanguage(language, SherpaNpuWhisperEngine.chip).filter { it.runsLive && Recording.models.isReady(it) }

/** The live model that runs for [language]: the one chosen on the start screen if it is ready, else the first ready. */
fun liveModelFor(language: String): SpeechModel? {
    val ready = readyLiveModels(language)
    return ready.firstOrNull { it.id == Recording.settings.liveModel(language) } ?: ready.firstOrNull()
}

/** [liveModelFor]'s model, or the next ready one when its NPU load doesn't fit in the free memory now. */
fun liveModelPick(language: String) = Recording.npuGuard.pick(liveModelFor(language), readyLiveModels(language))

/**
 * Live transcription for [language] with [liveModelFor]'s model, and speaker labels when the voice-print model is
 * downloaded, starting from the voices the app [known]; null when no live model is downloaded.
 */
private fun liveModel(language: String, scope: CoroutineScope, known: List<Voice>): LiveModel? {
    val pick = liveModelPick(language)
    val model = pick.model ?: return null
    val prints = Recording.voiceModel
    // With segmentation (live turns) the prints are of short pieces: their own, lower threshold.
    val segmentation = Recording.segmentationModel
    val liveTurns = prints != null && segmentation != null
    // With live turns: the threshold of this pair of models (VoicePrintThresholds.live), the user's slider on top.
    val threshold = prints?.let {
        val more = Recording.settings.speakerTuning(it.id)
        if (liveTurns) VoicePrintThresholds.live(it.id, segmentation!!.id, more) else VoicePrintThresholds.of(it.id, more).sameSpeaker
    }
    val tracker = prints?.let { SpeakerTracker(known, threshold!!, it.id) }
    // The model line shows the threshold in use: what to compare when speakers merge or split.
    val at = threshold?.let { " · " + "%.2f".format(java.util.Locale.ROOT, it) }.orEmpty()
    val inUse = prints?.let { MutableStateFlow(ModelInUse(it.name + at)) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val worker = Dispatchers.Default.limitedParallelism(1)
    // Speakers found live with segmentation when a model is on the phone; else by each line's print.
    val live = if (tracker != null && segmentation != null) {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val speakersWorker = Dispatchers.Default.limitedParallelism(1)
        var where = "CPU"
        LiveSpeakers(
            tracker, scope, speakersWorker,
            loadSegmenter = {
                val dir = Recording.models.dir(segmentation)
                // pyannote is small (0.1 s a window on one core); DiariZen's WavLM needs more on the CPU.
                val threads = if (segmentation == ModelCatalog.speakerSegmentation) 1 else 4
                SherpaSegmenter(File(dir, "model.onnx"), File(dir, SherpaSegmenter.NPU_FILE).takeIf { SherpaNpuWhisperEngine.chip == "SM8750" }, Recording.nativeLibDir, threads)
                    .also { where = if (it.onNpu) "NPU" else "CPU" }
            },
            loadEmbedder = {
                val turns = if (segmentation == ModelCatalog.speakerSegmentation) "pyannote" else segmentation.name
                Recording.voiceEmbedder(prints).also { e -> inUse?.value = ModelInUse("$turns · $where + ${prints.name}$at", e.runs) }
            },
        )
    } else {
        null
    }
    val labeler = if (live != null) null else tracker?.let { t ->
        SpeakerLabeler(t) { Recording.voiceEmbedder(prints).also { e -> inUse?.value = ModelInUse(prints.name + at, e.runs) } }
    }
    // Lines at pauses, or ~30 s windows for a model too slow for lines.
    val limits = model.liveWindowSeconds?.let(UtteranceLimits::window) ?: UtteranceLimits.lines
    val transcriber = when (model.kind) {
        ModelKind.Whisper, ModelKind.WhisperNpuEncoder -> {
            UtteranceTranscriber(language, model.id, scope, worker, labeler, limits) { Recording.npuEngine(model) { Recording.whisperEngine(model) } }
        }
        ModelKind.WhisperNpu -> {
            val dir = Recording.models.dir(model)
            UtteranceTranscriber(language, model.id, scope, worker, labeler, limits) {
                SherpaNpuWhisperEngine(dir, Recording.nativeLibDir).also { e -> Recording.npuGuard.load(model) { e.load(language) } }
            }
        }
        ModelKind.StreamingTransducer, ModelKind.StreamingTransducerNpu -> {
            val dir = Recording.models.dir(model)
            val npu = Recording.nativeLibDir.takeIf { model.kind == ModelKind.StreamingTransducerNpu }
            StreamingTranscriber(model.id, scope, worker, labeler) { Recording.npuEngine(model) { SherpaStreamingEngine(dir, npu) } }
        }
        ModelKind.SpeakerEmbedding, ModelKind.SpeakerSegmentation -> error("Not a speech model: ${model.id}")
    }
    return LiveModel(model.name, transcriber, tracker, inUse, live, segmentation?.id?.takeIf { live != null && segmentation != ModelCatalog.speakerSegmentation },
        skipped = pick.skipped?.name,
        improveOnStop = pick.skipped != null && Recording.improveModelFor(language) != null,
    )
}

class TranscriptorApp : Application(), ShedHost {
    /** Shed's background uploads run the queue of [Recording.backup] (it refreshes from shed when shown). */
    override val androidShed: AndroidShed get() = Recording.shed

    override fun onCreate() {
        super.onCreate()
        Recording.init(this)
    }
}
