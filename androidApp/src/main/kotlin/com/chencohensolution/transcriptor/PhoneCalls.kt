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
import com.chencohensolution.transcriptor.storage.LIBRARY_ROOT
import com.chencohensolution.transcriptor.storage.SafFileSystem
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
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
 * Android doesn't let the app start it from the background, the calls wait for the app to be opened on the charger.
 */
class CallsTranscribeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val (percent, charging) = Recording.battery(this)
        val waiting = runCatching { Recording.backup.callMoments && Recording.backup.callsWaitingForText().isNotEmpty() }.getOrDefault(false)
        if (!waiting) return false
        if (!charging || percent < ENCODE_MIN_BATTERY || Recording.session.isRecording || Recording.refining.value != null) {
            jobFinished(params, true)
            return true
        }
        runCatching { RecordingService.improvePending(this) }
            .onFailure { android.util.Log.w("CallsTranscribeJob", "not allowed to start transcribing from the background", it) }
        return false
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 4402

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
