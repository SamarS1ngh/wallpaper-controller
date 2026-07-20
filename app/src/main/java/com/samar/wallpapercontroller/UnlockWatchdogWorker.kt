package com.samar.wallpapercontroller

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.samar.wallpapercontroller.WallpaperStore.cycleOnInterval
import com.samar.wallpapercontroller.WallpaperStore.cycleOnUnlock
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled
import com.samar.wallpapercontroller.WallpaperStore.intervalMinutes
import java.util.concurrent.TimeUnit

/**
 * Re-arms whatever cycling mode is enabled, every 15 minutes. Moto's battery
 * manager ("SleepMode") kills the process outright, so START_STICKY never
 * fires for the unlock service; a force-stop additionally cancels scheduled
 * work until the next process start. Starting is idempotent: the service
 * start is a no-op onStartCommand when it's already up, and the interval
 * work is enqueued with KEEP so an existing schedule is untouched.
 */
class UnlockWatchdogWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (!context.cyclingEnabled) return Result.success()
        if (context.cycleOnUnlock) {
            val result = runCatching { UnlockCycleService.start(context) }
            result.exceptionOrNull()?.let {
                CycleLog.log(context, "watchdog: unlock service start REFUSED ${it.message}")
            }
        }
        if (context.cycleOnInterval) {
            LockCycleWorker.ensure(context, context.intervalMinutes)
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
