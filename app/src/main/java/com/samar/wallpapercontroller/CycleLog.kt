package com.samar.wallpapercontroller

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent on-device event log. Release builds are not debuggable (no
 * run-as, and logcat is long gone days later when cycling dies), so the app
 * records its own evidence: worker runs, service lifecycle, wallpaper
 * advances. Viewable from the Diagnostics button in [MainActivity].
 */
object CycleLog {

    private const val MAX_BYTES = 64 * 1024L

    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun file(context: Context) = File(context.filesDir, "cycle_log.txt")

    @Synchronized
    fun log(context: Context, event: String) {
        runCatching {
            val f = file(context)
            if (f.length() > MAX_BYTES) {
                // Drop the older half so the log never grows unbounded.
                val tail = f.readText()
                f.writeText(tail.substring(tail.length / 2).substringAfter('\n'))
            }
            f.appendText("${format.format(Date())}  $event\n")
        }
    }

    fun read(context: Context): String =
        runCatching { file(context).readText() }.getOrDefault("")
            .ifEmpty { "(no events yet)" }
}
