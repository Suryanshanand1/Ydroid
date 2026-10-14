package org.opensourcecommunity.ydroid.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service so downloads survive leaving the app. Killed process +
 * START_REDELIVER_INTENT lets the download resume (.part + --continue).
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_STOP -> {
                DownloadRepository.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROGRESS -> {
                val pct = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                val line = intent.getStringExtra(EXTRA_LINE) ?: ""
                startForegroundCompat(buildNotification(pct, line))
                return START_STICKY
            }
            ACTION_DONE -> {
                val line = intent.getStringExtra(EXTRA_LINE) ?: "Done"
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIF_ID, buildNotification(100f, line, ongoing = false))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    // process was killed and restarted: resume persisted job
                    startForegroundCompat(buildNotification(0f, "Resuming…"))
                    DownloadRepository.resumeIfPersisted(this) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return START_REDELIVER_INTENT
                }
                startForegroundCompat(buildNotification(0f, "Starting…"))
                return START_REDELIVER_INTENT
            }
        }
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(pct: Float, line: String, ongoing: Boolean = true): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Ydroid downloading ${pct.toInt()}%")
            .setContentText(line.take(120))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.toInt(), false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL = "ydroid_downloads"
        const val NOTIF_ID = 41
        const val ACTION_STOP = "ydroid.action.STOP"
        const val ACTION_PROGRESS = "ydroid.action.PROGRESS"
        const val ACTION_DONE = "ydroid.action.DONE"
        const val EXTRA_URL = "url"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_LINE = "line"

        fun start(ctx: Context, url: String) {
            val intent = Intent(ctx, DownloadService::class.java).putExtra(EXTRA_URL, url)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (_: Exception) { }
        }

        fun updateProgress(ctx: Context, progress: Float, line: String) {
            val intent = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_PROGRESS)
                .putExtra(EXTRA_PROGRESS, progress)
                .putExtra(EXTRA_LINE, line)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (_: Exception) { }
        }

        fun finish(ctx: Context, line: String) {
            val intent = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_DONE)
                .putExtra(EXTRA_LINE, line)
            try { ctx.startService(intent) } catch (_: Exception) { }
        }
    }
}
