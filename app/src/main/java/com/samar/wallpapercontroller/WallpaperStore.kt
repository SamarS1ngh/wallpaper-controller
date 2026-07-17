package com.samar.wallpapercontroller

import android.app.WallpaperManager
import android.content.Context
import android.net.Uri
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
    private const val KEY_INTERVAL_MIN = "interval_minutes"
    private const val KEY_CYCLING = "cycling_enabled"
    private const val KEY_NEXT_SEQ = "next_seq"

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

    fun setHomeWallpaper(context: Context) {
        val file = homeFile(context)
        if (!file.exists()) return
        file.inputStream().use {
            WallpaperManager.getInstance(context)
                .setStream(it, null, true, WallpaperManager.FLAG_SYSTEM)
        }
    }

    /** Applies the next lock wallpaper in sequence. Returns the file used, or null if the set is empty. */
    fun advanceLockWallpaper(context: Context): File? {
        val files = lockFiles(context)
        if (files.isEmpty()) return null
        val index = prefs(context).getInt(KEY_LOCK_INDEX, -1)
        val next = (index + 1).mod(files.size)
        val file = files[next]
        file.inputStream().use {
            WallpaperManager.getInstance(context)
                .setStream(it, null, true, WallpaperManager.FLAG_LOCK)
        }
        prefs(context).edit().putInt(KEY_LOCK_INDEX, next).apply()
        return file
    }

    var Context.intervalMinutes: Long
        get() = prefs(this).getLong(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)
        set(value) = prefs(this).edit().putLong(KEY_INTERVAL_MIN, value).apply()

    var Context.cyclingEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_CYCLING, false)
        set(value) = prefs(this).edit().putBoolean(KEY_CYCLING, value).apply()

    private fun copyUri(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open $uri")
    }
}
