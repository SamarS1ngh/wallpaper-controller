package com.samar.wallpapercontroller

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.service.wallpaper.WallpaperService
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import com.samar.wallpapercontroller.WallpaperStore.cycleOnUnlock
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled
import com.samar.wallpapercontroller.WallpaperStore.homeSpan

/**
 * Renders the chosen home image as a live wallpaper. The point is not the
 * rendering — the image is static — but the process guarantee: SystemUI keeps
 * the active wallpaper's process bound at all times, which OEM battery
 * managers (Moto "SleepMode") do not kill. That makes this process the one
 * reliable home for the screen-off lock-cycling hook; the foreground service
 * remains only as a fallback when the live wallpaper is not in use.
 */
class HomeWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = HomeEngine()

    inner class HomeEngine : Engine() {

        private val executor = Executors.newSingleThreadExecutor()
        private var receiverRegistered = false
        private var xOffset = 0f
        private var cached: Bitmap? = null
        private var cachedKey: String? = null

        private val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                val app = applicationContext
                if (!app.cyclingEnabled || !app.cycleOnUnlock) return
                executor.execute {
                    runCatching { WallpaperStore.advanceLockWallpaper(app) }
                        .onSuccess {
                            CycleLog.log(app, "live: advanced to ${it?.name ?: "none (empty or unreadable set)"}")
                        }
                        .onFailure { CycleLog.log(app, "live: FAILED ${it.message}") }
                }
            }
        }

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder) {
            super.onCreate(surfaceHolder)
            if (!isPreview) {
                registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
                receiverRegistered = true
                CycleLog.log(applicationContext, "live wallpaper engine created")
                // The engine now owns the screen-off hook; drop the fallback
                // service so its notification disappears and nothing advances twice.
                UnlockCycleService.stop(applicationContext)
            }
            engines.add(WeakReference(this))
        }

        override fun onDestroy() {
            if (receiverRegistered) {
                unregisterReceiver(screenOffReceiver)
                CycleLog.log(applicationContext, "live wallpaper engine destroyed")
            }
            executor.shutdown()
            engines.removeIf { it.get() == null || it.get() === this }
            super.onDestroy()
        }

        override fun onSurfaceChanged(
            holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int
        ) = requestDraw()

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) requestDraw()
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            this.xOffset = xOffset
            if (applicationContext.homeSpan) requestDraw()
        }

        fun requestDraw() {
            // runCatching also covers execute() itself: a redraw request can
            // race engine destruction and hit a shut-down executor.
            runCatching { executor.execute { runCatching { draw() } } }
        }

        private fun draw() {
            val holder = surfaceHolder ?: return
            val frame = holder.surfaceFrame
            val width = frame.width()
            val height = frame.height()
            if (width <= 0 || height <= 0) return
            val bitmap = bitmapFor(width, height)
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                if (bitmap != null) {
                    val overflow = (bitmap.width - width).coerceAtLeast(0)
                    val x = if (applicationContext.homeSpan) -xOffset * overflow else 0f
                    canvas.drawBitmap(bitmap, x, ((height - bitmap.height) / 2f), null)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun bitmapFor(width: Int, height: Int): Bitmap? {
            val app = applicationContext
            val file = WallpaperStore.homeFile(app)
            val key = "$width x$height span=${app.homeSpan} mod=${file.lastModified()}"
            if (key != cachedKey) {
                cached = WallpaperStore.homeBitmap(app, width, height)
                cachedKey = key
            }
            return cached
        }
    }

    companion object {
        private val engines = CopyOnWriteArrayList<WeakReference<HomeEngine>>()

        /** True when this app's live wallpaper is the active system wallpaper. */
        fun isActive(context: Context): Boolean =
            WallpaperManager.getInstance(context).wallpaperInfo?.component ==
                ComponentName(context, HomeWallpaperService::class.java)

        /** Redraws all live engines after the home image or span mode changed. */
        fun notifyHomeChanged() {
            engines.forEach { it.get()?.requestDraw() }
        }

        /** System screen that offers to set this live wallpaper, with preview. */
        fun pickerIntent(context: Context): Intent =
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, HomeWallpaperService::class.java)
            )
    }
}
