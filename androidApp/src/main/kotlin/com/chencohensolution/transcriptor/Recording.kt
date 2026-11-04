package com.chencohensolution.transcriptor

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
import com.chencohensolution.transcriptor.notebook.NotebookStore
import com.chencohensolution.transcriptor.notebook.Place
import com.chencohensolution.transcriptor.notebook.adoptLibrary
import com.chencohensolution.transcriptor.notebook.archiveFinished
import com.chencohensolution.transcriptor.storage.LIBRARY_ROOT
import com.chencohensolution.transcriptor.storage.SafFileSystem
import com.chencohensolution.transcriptor.notebook.NotebooksController
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
import com.chencohensolution.transcriptor.speech.ModelFiles
import com.chencohensolution.transcriptor.speech.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * The app's one recording session and what it needs, shared by [RecordingService] (which keeps it going with the
 * screen off) and [MainActivity] (which shows it). Files are in the app's private storage, `files/`.
 */
/** The folder made in the one the user picks, unless it is one already. */
private const val LIBRARY_FOLDER = "Transcriptor"

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

    /** The app's unpacked native libraries: Qualcomm's NPU runtime for [ModelKind.WhisperNpu]. */
    lateinit var nativeLibDir: String
        private set

    /** The last lecture stopped, for the start screen. */
    val lastLecture = MutableStateFlow<Lecture?>(null)

    /** The refine pass: the lecture it runs on and 0..1; null when not running. */
    val refining = MutableStateFlow<Pair<String, Float>?>(null)

    /** Compressing to FLAC: the lecture and 0..1, null when not. */
    val compressing = MutableStateFlow<Pair<String, Float>?>(null)

    /** The speaker pass: the lecture it runs on and 0..1, null when not running. */
    val findingSpeakers = MutableStateFlow<Pair<String, Float>?>(null)

    /** The passes running, by lecture: what the Notebooks screen shows under a lecture. */
    val working = combine(compressing, findingSpeakers, refining) { c, f, r -> listOfNotNull(c, f, r).toMap() }

    lateinit var notebooks: NotebookStore
        private set

    /** The notebooks and their lectures, for the Notebooks screen. */
    lateinit var library: NotebooksController
        private set

    /** The phone's offset from UTC at a time: weekdays and hours for the notebook guess and the transcript's date. */
    val utcOffset = { at: Long -> java.util.TimeZone.getDefault().getOffset(at).toLong() }


    /** The models the refine pass can use for [language]: every downloaded speech model for it, the large ones first. */
    fun refineModels(language: String): List<SpeechModel> =
        ModelCatalog.forLanguage(language, SherpaNpuWhisperEngine.chip)
            .filter { it.kind.transcribes && models.isReady(it) }
            .sortedBy { it.fitsLive }

    /**
     * The refine pass on [lecture] with [modelId] (minutes to hours with a large model; not on the main thread): the
     * current transcript kept as a version first, then every line transcribed again, edited lines kept. Saves and
     * returns it. [kept] is called once the version is kept, so an open review screen can read it.
     */
    fun refine(lecture: Lecture, modelId: String, kept: () -> Unit = {}, progress: (Float) -> Unit = {}): Lecture {
        val model = refineModels(lecture.language).firstOrNull { it.id == modelId } ?: error("$modelId isn't downloaded")
        store.repairAudio(lecture)
        val withVersion = store.keepCurrent(store.load(lecture.notebookId, lecture.id) ?: lecture, System.currentTimeMillis())
        store.save(withVersion)
        kept()
        val path = store.audioPath(lecture)
        val audio = { a: Long, b: Long -> RecordingAudio.range({ openAudio(path) }, lecture.audioFile == AUDIO_WAV, a, b) }
        val done = when (model.kind) {
            ModelKind.Whisper -> WhisperEngine(models.file(model).path).use { engine ->
                RefinePass(engine, lecture.language, audio).run(withVersion, model.id, progress)
            }
            ModelKind.WhisperNpu -> SherpaNpuWhisperEngine(models.dir(model), nativeLibDir).use { engine ->
                RefinePass(engine, lecture.language, audio).run(withVersion, model.id, progress)
            }
            else -> SherpaStreamingEngine(models.dir(model), nativeLibDir.takeIf { model.kind == ModelKind.StreamingTransducerNpu }).use { engine ->
                RefinePass({ samples -> transcribeLineStreaming(engine, samples) }, audio).run(withVersion, model.id, progress)
            }
        }
        // Saved over what is on disk now, with its edits since (the review screen may have changed it meanwhile):
        // only the lines' texts the pass wrote are taken.
        val now = store.load(lecture.notebookId, lecture.id) ?: withVersion
        val refined = done.segments.associateBy { it.id }
        val merged = now.copy(
            segments = now.segments.map { s -> refined[s.id]?.takeIf { !s.edited && it.modelId == model.id }?.let { s.copy(text = it.text, modelId = it.modelId, words = emptyList()) } ?: s },
            refinedWith = model.id,
        )
        store.save(merged)
        return merged
    }

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
        if (!models.isReady(ModelCatalog.voicePrints)) return null
        val path = store.audioPath(lecture)
        val audio = com.chencohensolution.transcriptor.speech.FloatBuilder()
        for (line in lecture.segments.filter { it.speakerId == speakerId }.sortedByDescending { it.endMillis - it.startMillis }) {
            if (audio.size >= 20 * 16_000) break
            audio.add(RecordingAudio.range({ openAudio(path) }, lecture.audioFile == AUDIO_WAV, line.startMillis, line.endMillis))
        }
        if (audio.size < SpeakerLabeler.MIN_SAMPLES) return null
        val embedder = SherpaVoiceEmbedder(models.file(ModelCatalog.voicePrints))
        return try { embedder.embed(audio.toArray()).toList() } finally { embedder.close() }
    }
    /** Both models of the speaker pass are downloaded. */
    val canFindSpeakers get() = models.isReady(ModelCatalog.speakerSegmentation) && models.isReady(ModelCatalog.voicePrints)

    /**
     * Runs the speaker pass on [lecture] (seconds to minutes; not on the main thread): who spoke when, the lines
     * relabeled and split to match, and the speakers named from the notebook's voices. Saves and returns it.
     */
    fun findSpeakers(lecture: Lecture, progress: (Float) -> Unit = {}): Lecture {
        store.repairAudio(lecture)
        val diarizer = SherpaDiarizer(models.file(ModelCatalog.speakerSegmentation), models.file(ModelCatalog.voicePrints))
        val embedder = SherpaVoiceEmbedder(models.file(ModelCatalog.voicePrints))
        try {
            RecordingAudio(openAudio(store.audioPath(lecture)), lecture.audioFile == AUDIO_WAV).use { audio ->
                val result = SpeakerPass(diarizer, embedder).run(audio, voices.load(lecture.notebookId)) { at ->
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

    /**
     * Files an unfiled lecture into a notebook (files: not on the main thread), and gives the notebook the voices of
     * the speakers named in it, so its next lectures label them by name.
     */
    fun file(lectureId: String, notebookId: String) {
        val lecture = library.assign(lectureId, notebookId) ?: return
        for (speaker in lecture.speakers.filter { it.name.isNotBlank() }) {
            runCatching { printOf(lecture, speaker.id) }.getOrNull()?.let { voices.save(notebookId, Voice(speaker.name, it)) }
        }
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
        val uri = Uri.parse(saved)
        val fs = SafFileSystem(resolver, uri)
        val name = runCatching { fs.name }.getOrDefault("")
        if (!canUse(uri) || name.isEmpty()) {
            folder.value = Folder.Lost(name.ifEmpty { uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "" })
            return
        }
        store.library = Place(fs, LIBRARY_ROOT)
        folder.value = Folder.Ready(name)
    }

    /** The app still holds the read and write permission the folder's tree was picked with. */
    private fun canUse(folderUri: Uri): Boolean {
        val tree = DocumentsContract.buildTreeDocumentUri(folderUri.authority, DocumentsContract.getTreeDocumentId(folderUri))
        return resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    }

    /**
     * Makes the folder the user picked ([tree], from the system's folder picker) the lectures folder (files: not on the
     * main thread): a `Transcriptor` folder in it unless it is one already (by name, or a library from before), an
     * empty `.nomedia` so music and gallery apps skip the recordings, then the notebooks, voices and finished lectures
     * move in ([adoptLibrary]), from the app or from the folder used before, whose permission is then given back.
     */
    fun chooseFolder(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(tree, flags)
        val treeFs = SafFileSystem(resolver, tree)
        val isLibrary = treeFs.name == LIBRARY_FOLDER || treeFs.exists(LIBRARY_ROOT / "notebooks.json")
        val folderUri = if (isLibrary) {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } else {
            treeFs.createDirectories(LIBRARY_ROOT / LIBRARY_FOLDER)
            DocumentsContract.buildDocumentUriUsingTree(tree, treeFs.documentId(LIBRARY_ROOT / LIBRARY_FOLDER))
        }
        val fs = SafFileSystem(resolver, folderUri)
        if (!fs.exists(LIBRARY_ROOT / ".nomedia")) fs.write(LIBRARY_ROOT / ".nomedia") {}
        val before = settings.lectureFolder
        val target = Place(fs, LIBRARY_ROOT)
        folder.value = Folder.Moving(fs.name, 0f)
        try {
            adoptLibrary(store, notebooks, target, busy()) { folder.value = Folder.Moving(fs.name, it) }
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

    /** The finished lectures still in the app move to the lectures folder (files: not on the main thread). */
    fun archive() {
        if (folder.value !is Folder.Ready) return
        if (archiveFinished(store) { it in busy() } > 0) library.reload()
    }

    fun init(app: Application) {
        val root = app.filesDir.toOkioPath()
        store = LectureStore(FileSystem.SYSTEM, root, utcOffset)
        settings = SettingsStore(FileSystem.SYSTEM, root)
        notebooks = NotebookStore { store.library ?: store.local }
        resolver = app.contentResolver
        connectLibrary()
        // Before notebooks, every lecture was in "default": they become unfiled, once.
        runCatching { notebooks.migrate(store) }.onFailure { android.util.Log.e("Recording", "notebook migration failed", it) }
        library = NotebooksController(notebooks, store, System::currentTimeMillis)
        models = ModelFiles(app)
        nativeLibDir = app.applicationInfo.nativeLibraryDir
        session = LectureSession(
            AndroidRecorder(), store, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            voices = VoiceStore { store.library ?: store.local }.also { voices = it },
            live = ::liveModel,
        )
        compression = FlacCompression(FileSystem.SYSTEM, store, androidFlacEncode)
        review = LectureReview(store, voices, AndroidAudioPlayer(::openAudio), CoroutineScope(SupervisorJob() + Dispatchers.Main), ::printOf)
    }
}

/** The live models for [language] that are downloaded, the language's own first (ivrit.ai's for Hebrew). */
fun readyLiveModels(language: String) =
    ModelCatalog.forLanguage(language, SherpaNpuWhisperEngine.chip).filter { it.fitsLive && Recording.models.isReady(it) }

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
        ModelKind.WhisperNpu -> {
            val dir = Recording.models.dir(model)
            UtteranceTranscriber(language, model.id, scope, worker, labeler) {
                SherpaNpuWhisperEngine(dir, Recording.nativeLibDir).apply { load(language) }
            }
        }
        ModelKind.StreamingTransducer, ModelKind.StreamingTransducerNpu -> {
            val dir = Recording.models.dir(model)
            val npu = Recording.nativeLibDir.takeIf { model.kind == ModelKind.StreamingTransducerNpu }
            StreamingTranscriber(model.id, scope, worker, labeler) { SherpaStreamingEngine(dir, npu) }
        }
        ModelKind.SpeakerEmbedding, ModelKind.SpeakerSegmentation -> error("Not a speech model: ${model.id}")
    }
    return LiveModel(model.name, transcriber, tracker)
}

class TranscriptorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Recording.init(this)
    }
}
