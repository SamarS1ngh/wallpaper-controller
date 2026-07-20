package com.samar.wallpapercontroller

import android.app.Application

/**
 * Lock cycling runs entirely inside the live wallpaper engine, which SystemUI
 * keeps bound and restarts on boot, so there is nothing to re-arm here. Every
 * process start is still logged as a marker for the in-app diagnostics.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CycleLog.log(this, "process start")
    }
}
