package com.hiep.lightbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Giữ ứng dụng sống khi phát video/âm thanh ở chế độ nền. */
class MediaService : Service() {

    companion object {
        private const val CHANNEL = "media_playback"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"

        @Volatile
        private var running = false

        fun update(ctx: Context, title: String, playing: Boolean) {
            val i = Intent(ctx, MediaService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PLAYING, playing)
            try {
                if (running) ctx.startService(i) else ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, MediaService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(title, playing),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (playing) acquireWakeLock() else releaseWakeLock()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        running = false
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lightbrowser:media").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            code,
            Intent(name).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(if (playing) R.string.notif_playing else R.string.notif_paused))
            .setContentText(title.ifBlank { getString(R.string.app_name) })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_notify,
                getString(if (playing) R.string.notif_pause else R.string.notif_play),
                action(MainActivity.ACTION_TOGGLE, 1)
            )
            .addAction(
                R.drawable.ic_close,
                getString(R.string.notif_close),
                action(MainActivity.ACTION_CLOSE, 2)
            )
            .build()
    }
}
