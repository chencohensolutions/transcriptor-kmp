package com.chencohensolution.transcriptor

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.chencohensolution.transcriptor.settings.ENCODE_MIN_BATTERY

/**
 * "Encode when charging": lectures recorded as WAV for FLAC or AAC wait for the charger and [ENCODE_MIN_BATTERY]%
 * battery. The system starts this job once the phone is on the charger (the app needn't be open, and it survives a
 * restart); below the battery it asks to run again later. The encoding runs in the job itself, a lecture at a time; a
 * job the system stops is run again, and a lecture cut off is encoded again from its WAV.
 */
class EncodeJob : JobService() {
    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val (percent, charging) = Recording.battery(this)
        if (!charging || percent < ENCODE_MIN_BATTERY || Recording.session.isRecording) {
            jobFinished(params, true)
            return true
        }
        thread = Thread({
            Recording.encodePending(this)
            // Something still waits (a recording started meanwhile, or one failed): later.
            jobFinished(params, Recording.compression.pending().isNotEmpty())
        }, "encode-job").apply { start() }
        return true
    }

    /** The charger came out or time ran out: the lecture being encoded finishes or is redone next time. */
    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 4401

        /** Asks the system to run the job on the charger (once; again on boot), unless it is already scheduled. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(context, EncodeJob::class.java))
                    .setRequiresCharging(true)
                    .setPersisted(true)
                    .setBackoffCriteria(15 * 60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                    .build(),
            )
        }
    }
}
