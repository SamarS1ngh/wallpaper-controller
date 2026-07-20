package com.samar.wallpapercontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

/**
 * Advances the lock wallpaper every time the screen turns off, so each unlock
 * greets the user with the next image in the set. A foreground service is the
 * only way to keep a SCREEN_OFF receiver registered (that broadcast cannot be
 * declared in the manifest).
 */
class UnlockCycleService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                executor.execute {
                    runCatching { WallpaperStore.advanceLockWallpaper(applicationContext) }
                        .onSuccess {
                            CycleLog.log(
                                applicationContext,
                                "unlock: advanced to ${it?.name ?: "none (empty or unreadable set)"}"
                            )
                        }
                        .onFailure {
                            CycleLog.log(applicationContext, "unlock: FAILED ${it.message}")
                        }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.unlock_channel_name),
            NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_next)
            .setContentTitle(getString(R.string.unlock_service_title))
            .setContentText(getString(R.string.unlock_service_text))
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        CycleLog.log(this, "unlock service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        CycleLog.log(this, "unlock service stopped")
        unregisterReceiver(screenOffReceiver)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "unlock_cycle"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UnlockCycleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UnlockCycleService::class.java))
        }
    }
}
