package com.chencohensolution.transcriptor

import android.app.Application
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.recording.AndroidRecorder
import com.chencohensolution.transcriptor.recording.FlacCompression
import com.chencohensolution.transcriptor.recording.LectureSession
import com.chencohensolution.transcriptor.recording.androidFlacEncode
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.recording.LiveModel
import com.chencohensolution.transcriptor.settings.SettingsStore
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.notebook.VoiceStore
import com.chencohensolution.transcriptor.speech.SherpaStreamingEngine
import com.chencohensolution.transcriptor.speech.SherpaVoiceEmbedder
import com.chencohensolution.transcriptor.speech.SpeakerLabeler
import com.chencohensolution.transcriptor.speech.SpeakerTracker
import com.chencohensolution.transcriptor.speech.Voice
import com.chencohensolution.transcriptor.speech.StreamingTranscriber
import com.chencohensolution.transcriptor.speech.UtteranceTranscriber
import com.chencohensolution.transcriptor.speech.ModelFiles
import com.chencohensolution.transcriptor.speech.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * The app's one recording session and what it needs, shared by [RecordingService] (which keeps it going with the
 * screen off) and [MainActivity] (which shows it). Files are in the app's private storage, `files/`.
 */
object Recording {
    lateinit var store: LectureStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var session: LectureSession
        private set
    lateinit var compression: FlacCompression
        private set
    lateinit var models: ModelFiles
        private set

    /** The last lecture stopped, for the start screen. */
    val lastLecture = MutableStateFlow<Lecture?>(null)

    /** Compressing to FLAC: 0..1, null when not. */
    val compressing = MutableStateFlow<Float?>(null)

    fun init(app: Application) {
        val root = app.filesDir.toOkioPath()
        store = LectureStore(FileSystem.SYSTEM, root)
        settings = SettingsStore(FileSystem.SYSTEM, root)
        models = ModelFiles(app)
        session = LectureSession(
            AndroidRecorder(), store, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            voices = VoiceStore(FileSystem.SYSTEM, root),
            live = ::liveModel,
        )
        compression = FlacCompression(FileSystem.SYSTEM, store, androidFlacEncode)
    }
}

/** The live models for [language] that are downloaded, the language's own first (ivrit.ai's for Hebrew). */
fun readyLiveModels(language: String) =
    ModelCatalog.forLanguage(language).filter { it.fitsLive && Recording.models.isReady(it) }

/** The live model that runs for [language]: the one chosen on the start screen if it is ready, else the first ready. */
fun liveModelFor(language: String): SpeechModel? {
    val ready = readyLiveModels(language)
    return ready.firstOrNull { it.id == Recording.settings.liveModel(language) } ?: ready.firstOrNull()
}

/**
 * Live transcription for [language] with [liveModelFor]'s model, and speaker labels when the voice-print model is
 * downloaded, starting from the voices the notebook [known]; null when no live model is downloaded.
 */
private fun liveModel(language: String, scope: CoroutineScope, known: List<Voice>): LiveModel? {
    val model = liveModelFor(language) ?: return null
    val prints = ModelCatalog.voicePrints
    val tracker = if (Recording.models.isReady(prints)) SpeakerTracker(known) else null
    val labeler = tracker?.let { t -> SpeakerLabeler(t) { SherpaVoiceEmbedder(Recording.models.file(prints)) } }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val worker = Dispatchers.Default.limitedParallelism(1)
    val transcriber = when (model.kind) {
        ModelKind.Whisper -> {
            val path = Recording.models.file(model).path
            UtteranceTranscriber(language, model.id, scope, worker, labeler) { WhisperEngine(path) }
        }
        ModelKind.StreamingTransducer -> {
            val dir = Recording.models.dir(model)
            StreamingTranscriber(model.id, scope, worker, labeler) { SherpaStreamingEngine(dir) }
        }
        ModelKind.SpeakerEmbedding -> error("Not a speech model: ${model.id}")
    }
    return LiveModel(model.name, transcriber, tracker)
}

class TranscriptorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Recording.init(this)
    }
}
