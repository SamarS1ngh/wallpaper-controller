package com.samar.wallpapercontroller

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

/**
 * Owns the app's copies of the chosen images and the cycling state.
 *
 * Home wallpaper lives at filesDir/home_wallpaper.
 * Lock wallpapers live under filesDir/lock/, named by insertion order.
 */
object WallpaperStore {

    private const val PREFS = "wallpaper_store"
    private const val KEY_LOCK_INDEX = "lock_index"
    private const val KEY_CYCLING = "cycling_enabled"
    private const val KEY_NEXT_SEQ = "next_seq"
    private const val KEY_HOME_SPAN = "home_span"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun homeFile(context: Context): File = File(context.filesDir, "home_wallpaper")

    fun lockDir(context: Context): File =
        File(context.filesDir, "lock").apply { mkdirs() }

    fun lockFiles(context: Context): List<File> =
        lockDir(context).listFiles()
            ?.filterNot { it.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun importHome(context: Context, uri: Uri): File {
        val dest = homeFile(context)
        copyUri(context, uri, dest)
        return dest
    }

    /**
     * Copies each picked image into the lock set. One bad image (unreadable
     * cloud URI, interrupted copy) no longer aborts the rest of the batch.
     * Returns imported count; caller compares against uris.size to report skips.
     */
    /**
     * Result: [imported] new images added, [duplicates] already in the set and
     * skipped (the picker can re-deliver a stale selection after process death).
     */
    data class ImportResult(val imported: Int, val duplicates: Int)

    fun importLock(context: Context, uris: List<Uri>): ImportResult {
        var seq = prefs(context).getLong(KEY_NEXT_SEQ, 0L)
        var imported = 0
        var duplicates = 0
        val known = lockFiles(context).map(::sha1).toMutableSet()
        for (uri in uris) {
            val result = runCatching {
                val dest = File(lockDir(context), "%06d".format(seq))
                if (!copyUriUnlessDuplicate(context, uri, dest, known)) return@runCatching false
                known.add(sha1(dest))
                true
            }
            result.exceptionOrNull()?.let {
                android.util.Log.e("WallpaperStore", "import failed for $uri", it)
            }
            when (result.getOrNull()) {
                true -> {
                    seq++
                    imported++
                    prefs(context).edit().putLong(KEY_NEXT_SEQ, seq).apply()
                }
                false -> duplicates++
                null -> Unit
            }
        }
        return ImportResult(imported, duplicates)
    }

    fun removeLock(context: Context, file: File) {
        file.delete()
    }

    fun clearLock(context: Context) {
        lockFiles(context).forEach { it.delete() }
        prefs(context).edit().putInt(KEY_LOCK_INDEX, 0).apply()
    }

    /**
     * Applies the home wallpaper. When the live wallpaper is active it renders
     * the image itself — calling setBitmap/setStream then would silently kick
     * the live wallpaper out, so only a redraw is requested. In span mode the
     * original image is handed to the launcher untouched so it can
     * parallax-scroll across pages; otherwise it is center-cropped to exactly
     * the display size so every page shows the same view.
     */
    fun setHomeWallpaper(context: Context) {
        val file = homeFile(context)
        if (!file.exists()) return
        if (HomeWallpaperService.isActive(context)) {
            HomeWallpaperService.notifyHomeChanged()
            return
        }
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
     * Applies the next lock wallpaper in sequence, center-cropped to cover the
     * display exactly (aspect preserved, overflow trimmed, no bars, no stretch).
     * Returns the file used, or null if the set is empty.
     */
    fun advanceLockWallpaper(context: Context): File? {
        val files = lockFiles(context)
        if (files.isEmpty()) return null
        val index = prefs(context).getInt(KEY_LOCK_INDEX, -1)
        val (width, height) = displaySize(context)
        // A corrupt file (e.g. interrupted import) must not wedge the rotation:
        // skip past it instead of retrying it forever.
        for (step in 1..files.size) {
            val next = (index + step).mod(files.size)
            val file = files[next]
            val source = decodeForTarget(file, width, height) ?: continue
            WallpaperManager.getInstance(context)
                .setBitmap(centerCrop(source, width, height), null, true, WallpaperManager.FLAG_LOCK)
            prefs(context).edit().putInt(KEY_LOCK_INDEX, next).apply()
            return file
        }
        return null
    }

    /**
     * Decodes the home image sized for a surface of [width]x[height]: span mode
     * scales to the surface height keeping aspect (width overflows for
     * parallax), crop mode fills the surface exactly.
     */
    fun homeBitmap(context: Context, width: Int, height: Int): Bitmap? {
        val file = homeFile(context)
        if (!file.exists()) return null
        val source = decodeForTarget(file, width, height) ?: return null
        return if (context.homeSpan) {
            val scale = height.toFloat() / source.height
            Bitmap.createScaledBitmap(
                source, (source.width * scale).toInt().coerceAtLeast(1), height, true
            )
        } else {
            centerCrop(source, width, height)
        }
    }

    var Context.cyclingEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_CYCLING, false)
        set(value) = prefs(this).edit().putBoolean(KEY_CYCLING, value).apply()

    var Context.homeSpan: Boolean
        get() = prefs(this).getBoolean(KEY_HOME_SPAN, true)
        set(value) = prefs(this).edit().putBoolean(KEY_HOME_SPAN, value).apply()

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

    private fun copyUri(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open $uri")
    }

    /** Returns false (and writes nothing) when the image is already in the set. */
    private fun copyUriUnlessDuplicate(
        context: Context,
        uri: Uri,
        dest: File,
        known: Set<String>,
    ): Boolean {
        // Copy via a temp file so a kill mid-copy never leaves a truncated
        // image sitting in the lock set.
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Cannot open $uri")
            if (sha1(tmp) in known) return false
            if (!tmp.renameTo(dest)) throw IllegalStateException("Cannot move into $dest")
            return true
        } finally {
            tmp.delete()
        }
    }

    private fun sha1(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
