package com.samar.wallpapercontroller

import android.app.Application
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled

/**
 * Every process start is a chance to self-heal. A Moto force-stop cancels all
 * scheduled work and alarms, and nothing runs again until something revives
 * the process (opening the app, tapping the quick-settings tile, a reboot).
 * Whenever that happens, re-arm the watchdog so cycling resumes without the
 * user having to stop/start it by hand.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CycleLog.log(this, "process start")
        if (cyclingEnabled) UnlockWatchdogWorker.start(this)
    }
}
