package com.samar.wallpapercontroller

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class LockCycleWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val file = WallpaperStore.advanceLockWallpaper(applicationContext)
            CycleLog.log(
                applicationContext,
                "interval: advanced to ${file?.name ?: "none (empty or unreadable set)"}"
            )
            Result.success()
        } catch (e: Exception) {
            CycleLog.log(applicationContext, "interval: FAILED ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "lock_cycle"

        fun start(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<LockCycleWorker>(
                intervalMinutes, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        /** Enqueues the periodic work only if it isn't already scheduled. */
        fun ensure(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<LockCycleWorker>(
                intervalMinutes, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        fun advanceNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<LockCycleWorker>().build())
        }
    }
}
