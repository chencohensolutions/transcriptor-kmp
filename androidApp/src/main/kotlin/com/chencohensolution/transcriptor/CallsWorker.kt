package com.chencohensolution.transcriptor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Looks in the calls folder now and then for new calls while the app is closed, and hands them to shed to send
 * ([Recording.backUpCalls]); shed's own work sends them. Does nothing while calls aren't backed up.
 */
class CallsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Recording.backup.callsFolder != null) Recording.backUpCalls(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK = "audio-logs-calls"

        /** Every few hours, kept across restarts; scheduling again keeps the one there. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CallsWorker>(3, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
