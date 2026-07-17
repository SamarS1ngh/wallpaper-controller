package com.samar.wallpapercontroller

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

enum class CycleMode { INTERVAL, ON_UNLOCK, MANUAL }

/**
 * Owns the app's copies of the chosen images and the cycling state.
 *
 * Home wallpaper lives at filesDir/home_wallpaper.
 * Lock wallpapers live under filesDir/lock/, named by insertion order.
 */
object WallpaperStore {

    private const val PREFS = "wallpaper_store"
    private const val KEY_LOCK_INDEX = "lock_index"
    private const val KEY_INTERVAL_MIN = "interval_minutes"
    private const val KEY_CYCLING = "cycling_enabled"
    private const val KEY_NEXT_SEQ = "next_seq"
    private const val KEY_HOME_SPAN = "home_span"
    private const val KEY_CYCLE_MODE = "cycle_mode"

    const val DEFAULT_INTERVAL_MIN = 30L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun homeFile(context: Context): File = File(context.filesDir, "home_wallpaper")

    fun lockDir(context: Context): File =
        File(context.filesDir, "lock").apply { mkdirs() }

    fun lockFiles(context: Context): List<File> =
        lockDir(context).listFiles()?.sortedBy { it.name } ?: emptyList()

    fun importHome(context: Context, uri: Uri): File {
        val dest = homeFile(context)
        copyUri(context, uri, dest)
        return dest
    }

    fun importLock(context: Context, uris: List<Uri>) {
        var seq = prefs(context).getLong(KEY_NEXT_SEQ, 0L)
        for (uri in uris) {
            copyUri(context, uri, File(lockDir(context), "%06d".format(seq)))
            seq++
        }
        prefs(context).edit().putLong(KEY_NEXT_SEQ, seq).apply()
    }

    fun removeLock(context: Context, file: File) {
        file.delete()
    }

    fun clearLock(context: Context) {
        lockFiles(context).forEach { it.delete() }
        prefs(context).edit().putInt(KEY_LOCK_INDEX, 0).apply()
    }

    /**
     * Applies the home wallpaper. In span mode the original image is handed to the
     * launcher untouched so it can parallax-scroll across pages; otherwise it is
     * center-cropped to exactly the display size so every page shows the same view.
     */
    fun setHomeWallpaper(context: Context) {
        val file = homeFile(context)
        if (!file.exists()) return
        val manager = WallpaperManager.getInstance(context)
        if (context.homeSpan) {
            file.inputStream().use {
                manager.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM)
            }
        } else {
            val (width, height) = displaySize(context)
            val source = decodeForTarget(file, width, height) ?: return
            manager.setBitmap(centerCrop(source, width, height), null, true, WallpaperManager.FLAG_SYSTEM)
        }
    }

    /**
     * Applies the next lock wallpaper in sequence, fit-centered on a black canvas
     * sized to the display (never cropped or spanned). Returns the file used, or
     * null if the set is empty.
     */
    fun advanceLockWallpaper(context: Context): File? {
        val files = lockFiles(context)
        if (files.isEmpty()) return null
        val index = prefs(context).getInt(KEY_LOCK_INDEX, -1)
        val next = (index + 1).mod(files.size)
        val file = files[next]
        val (width, height) = displaySize(context)
        val source = decodeForTarget(file, width, height) ?: return null
        WallpaperManager.getInstance(context)
            .setBitmap(fitCenter(source, width, height), null, true, WallpaperManager.FLAG_LOCK)
        prefs(context).edit().putInt(KEY_LOCK_INDEX, next).apply()
        return file
    }

    var Context.intervalMinutes: Long
        get() = prefs(this).getLong(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)
        set(value) = prefs(this).edit().putLong(KEY_INTERVAL_MIN, value).apply()

    var Context.cyclingEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_CYCLING, false)
        set(value) = prefs(this).edit().putBoolean(KEY_CYCLING, value).apply()

    var Context.homeSpan: Boolean
        get() = prefs(this).getBoolean(KEY_HOME_SPAN, true)
        set(value) = prefs(this).edit().putBoolean(KEY_HOME_SPAN, value).apply()

    var Context.cycleMode: CycleMode
        get() = CycleMode.valueOf(
            prefs(this).getString(KEY_CYCLE_MODE, CycleMode.INTERVAL.name)!!
        )
        set(value) = prefs(this).edit().putString(KEY_CYCLE_MODE, value.name).apply()

    private fun displaySize(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /** Decodes the file downsampled to roughly the target size to avoid OOM on large photos. */
    private fun decodeForTarget(file: File, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width &&
            bounds.outHeight / (sample * 2) >= height
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.path, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val cropWidth = (width / scale).toInt().coerceIn(1, source.width)
        val cropHeight = (height / scale).toInt().coerceIn(1, source.height)
        val x = (source.width - cropWidth) / 2
        val y = (source.height - cropHeight) / 2
        val cropped = Bitmap.createBitmap(source, x, y, cropWidth, cropHeight)
        return Bitmap.createScaledBitmap(cropped, width, height, true)
    }

    private fun fitCenter(source: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        canvas.drawBitmap(
            source, null,
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    private fun copyUri(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open $uri")
    }
}
