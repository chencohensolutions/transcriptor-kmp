package com.chencohensolution.transcriptor

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.provider.CallLog
import com.chencohensolution.transcriptor.backup.CallLogEntry
import com.chencohensolution.transcriptor.notebook.CALL_IN
import com.chencohensolution.transcriptor.notebook.CALL_OUT
import com.chencohensolution.transcriptor.settings.ENCODE_MIN_BATTERY
import com.chencohensolution.transcriptor.speech.RefinePass
import com.chencohensolution.transcriptor.storage.LIBRARY_ROOT
import com.chencohensolution.transcriptor.storage.SafFileSystem
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The phone's own call recordings as moments (hen, Nov 17, 2026): what Android tells about them. The calls folder is
 * read through the documents provider only ([SafFileSystem]); nothing in it is written, moved or deleted.
 */
object PhoneCalls {
    /** Where a call moment's audio is ([com.chencohensolution.transcriptor.notebook.LectureStore.calls]): this, then its path in the calls folder. */
    val CALLS_ROOT: Path = "/calls".toPath()

    /** The calls folder under [CALLS_ROOT], for the moments' audio (the lectures folder has [LIBRARY_ROOT] already). */
    fun asCallsRoot(folder: SafFileSystem): FileSystem = object : ForwardingFileSystem(folder) {
        override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path = toFolder(path)
        override fun onPathResult(path: Path, functionName: String): Path =
            if (path == LIBRARY_ROOT) CALLS_ROOT else if (path.toString().startsWith("$LIBRARY_ROOT/")) CALLS_ROOT / path.relativeTo(LIBRARY_ROOT).toString() else path
    }

    /** A path under [CALLS_ROOT] as the calls folder's own file system names it. */
    fun toFolder(path: Path): Path =
        if (path == CALLS_ROOT) LIBRARY_ROOT else if (path.toString().startsWith("$CALLS_ROOT/")) LIBRARY_ROOT / path.relativeTo(CALLS_ROOT).toString() else path

    /** Whether [path] is a call's audio ([CALLS_ROOT]). */
    fun isCall(path: Path) = path.toString().startsWith("$CALLS_ROOT/")

    /** How long the call at [path] of the calls folder [fs] lasts, in milliseconds; null when Android can't tell. */
    fun duration(fs: FileSystem, path: Path): Long? {
        val folder = fs as? SafFileSystem ?: return null
        return folder.openFd(path).use { fd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(fd.fileDescriptor)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }
    }

    /** The app may read the call log (asked when Show calls in the log is turned on). */
    fun canReadLog(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /**
     * The call log's entry for the call that started nearest [startedAtMillis] (within 2 minutes; Samsung names the
     * file by when recording started, the log by when the call did), its direction and number; null without the
     * permission or a fitting entry.
     */
    fun logEntry(context: Context, startedAtMillis: Long, durationMillis: Long): CallLogEntry? {
        if (!canReadLog(context)) return null
        val window = 2 * 60_000L
        val rows = mutableListOf<Triple<Long, Int, String?>>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.NUMBER, CallLog.Calls.DURATION),
            "${CallLog.Calls.DATE} BETWEEN ? AND ?",
            arrayOf((startedAtMillis - window).toString(), (startedAtMillis + window).toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) rows += Triple(c.getLong(0), c.getInt(1), c.getString(2))
        }
        val best = rows.minByOrNull { abs(it.first - startedAtMillis) } ?: return null
        val direction = when (best.second) {
            CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CALL_IN
            CallLog.Calls.OUTGOING_TYPE -> CALL_OUT
            else -> null
        }
        return CallLogEntry(direction, best.third?.takeIf { it.isNotBlank() })
    }
}

/**
 * Transcribing the phone's calls on the charger with [ENCODE_MIN_BATTERY]% battery: the system starts this job once the
 * phone charges (the app needn't be open), and it starts the transcribe pass ([RecordingService.improvePending]). When
 * Android doesn't let the app start that from the background (the app isn't Battery › Unrestricted), the job
 * transcribes the calls itself, one after another, until the phone is unplugged, the system stops the job, or none is
 * left; then it asks to run again on the next charge.
 */
class CallsTranscribeJob : JobService() {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** The system stopped the job (or the phone was unplugged): the call being transcribed stops at its next line. */
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        val (percent, charging) = Recording.battery(this)
        val waiting = runCatching { Recording.backup.callMoments && Recording.backup.callsWaitingForText().isNotEmpty() }.getOrDefault(false)
        if (!waiting) return false
        if (!charging || percent < ENCODE_MIN_BATTERY || Recording.session.isRecording || Recording.refining.value != null) {
            android.util.Log.i(TAG, "calls wait: charging=$charging battery=$percent% recording=${Recording.session.isRecording} busy=${Recording.refining.value != null}")
            jobFinished(params, true)
            return true
        }
        val started = runCatching { RecordingService.improvePending(this) }
            .onFailure { android.util.Log.w(TAG, "not allowed to start transcribing from the background: transcribing here", it) }
            .isSuccess
        if (started) return false
        stopped = false
        scope.launch {
            val more = runCatching { transcribeCalls() }.onFailure { android.util.Log.e(TAG, "transcribing calls failed", it) }.getOrDefault(true)
            // Calls left (unplugged, stopped, or one failed): again on the next charge.
            jobFinished(params, more)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }

    /** Transcribes the waiting calls newest first; true when some are left for later. */
    private suspend fun transcribeCalls(): Boolean {
        val failed = mutableSetOf<String>()
        while (true) {
            val (percent, charging) = Recording.battery(this)
            if (stopped || !charging || percent < ENCODE_MIN_BATTERY || Recording.session.isRecording || Recording.refining.value != null) return true
            val next = Recording.nextCallToTranscribe(failed) ?: return Recording.backup.callsWaitingForText().any { it !in failed }.also {
                if (it) android.util.Log.i(TAG, "calls left that can't be transcribed now")
            }
            val (lecture, model) = next
            if (!Recording.npuGuard.fitsNow(model)) {
                android.util.Log.i(TAG, "not enough free memory for ${model.name} now: calls wait")
                return true
            }
            Recording.refining.value = lecture.id to 0f
            try {
                Recording.audioHere(lecture)
                val done = Recording.refine(
                    lecture, model.id,
                    progress = { p -> Recording.refining.value = lecture.id to p },
                    stop = { stopped || Recording.session.isRecording || !Recording.battery(this).second },
                )
                android.util.Log.i(TAG, "call ${lecture.id} transcribed: ${done.segments.size} lines")
                if (done.callFile != null && !done.pendingTranscript) Recording.callTranscribed(this)
            } catch (e: RefinePass.Stopped) {
                Recording.modelReleased()
                return true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "transcribing call ${lecture.id} failed", e)
                failed += lecture.id
            } finally {
                Recording.refining.value = null
            }
        }
    }

    override fun onDestroy() {
        stopped = true
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 4402
        private const val TAG = "CallsTranscribeJob"

        /** Asks the system to run the job on the charger (once; again on boot), unless it is already scheduled. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(context, CallsTranscribeJob::class.java))
                    .setRequiresCharging(true)
                    .setPersisted(true)
                    .setBackoffCriteria(30 * 60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                    .build(),
            )
        }
    }
}
