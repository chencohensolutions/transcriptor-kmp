package com.chencohensolution.transcriptor

import android.app.Application
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.recording.AndroidRecorder
import com.chencohensolution.transcriptor.recording.FlacCompression
import com.chencohensolution.transcriptor.recording.LectureSession
import com.chencohensolution.transcriptor.recording.androidFlacEncode
import com.chencohensolution.transcriptor.settings.SettingsStore
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

    /** The last lecture stopped, for the start screen. */
    val lastLecture = MutableStateFlow<Lecture?>(null)

    /** Compressing to FLAC: 0..1, null when not. */
    val compressing = MutableStateFlow<Float?>(null)

    fun init(app: Application) {
        val root = app.filesDir.toOkioPath()
        store = LectureStore(FileSystem.SYSTEM, root)
        settings = SettingsStore(FileSystem.SYSTEM, root)
        session = LectureSession(AndroidRecorder(), store, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        compression = FlacCompression(FileSystem.SYSTEM, store, androidFlacEncode)
    }
}

class TranscriptorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Recording.init(this)
    }
}
