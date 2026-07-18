package com.samar.wallpapercontroller

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.samar.wallpapercontroller.WallpaperStore.cycleOnUnlock
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled
import java.util.concurrent.TimeUnit

/**
 * Restarts [UnlockCycleService] if the OEM battery manager killed it (Moto
 * "SleepMode" kills the process outright, so START_STICKY never fires).
 * Starting is idempotent: if the service is already up this is a no-op
 * onStartCommand. The start can still be rejected while the app is
 * background-restricted; the battery-exemption prompt in MainActivity is what
 * makes it reliably succeed.
 */
class UnlockWatchdogWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (context.cyclingEnabled && context.cycleOnUnlock) {
            runCatching { UnlockCycleService.start(context) }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "unlock_watchdog"

        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<UnlockWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
