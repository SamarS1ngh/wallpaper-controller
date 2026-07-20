package com.samar.wallpapercontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.samar.wallpapercontroller.WallpaperStore.cycleOnUnlock
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled

/**
 * Restarts cycling after a reboot: the unlock service directly, and the
 * watchdog which re-arms whichever modes are enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.cyclingEnabled) return
        CycleLog.log(context, "boot: resync")
        UnlockWatchdogWorker.start(context)
        if (context.cycleOnUnlock) {
            runCatching { UnlockCycleService.start(context) }
        }
    }
}
