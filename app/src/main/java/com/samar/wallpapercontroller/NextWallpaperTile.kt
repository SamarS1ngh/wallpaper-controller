package com.samar.wallpapercontroller

import android.service.quicksettings.TileService
import kotlin.concurrent.thread

/**
 * Quick Settings tile: tap to advance the lock wallpaper. The tile is reachable
 * from the shade even on the lock screen, which is the closest a normal app can
 * get to a lock-screen gesture (touch on the lock screen itself is off-limits).
 */
class NextWallpaperTile : TileService() {
    override fun onClick() {
        thread {
            runCatching { WallpaperStore.advanceLockWallpaper(applicationContext) }
        }
    }
}
