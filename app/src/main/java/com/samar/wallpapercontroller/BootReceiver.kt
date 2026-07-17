package com.samar.wallpapercontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.samar.wallpapercontroller.WallpaperStore.cycleMode
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled

/**
 * Restarts unlock-mode cycling after a reboot. Interval mode needs no help here:
 * WorkManager re-schedules its own periodic work.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (context.cyclingEnabled && context.cycleMode == CycleMode.ON_UNLOCK) {
            runCatching { UnlockCycleService.start(context) }
        }
    }
}
