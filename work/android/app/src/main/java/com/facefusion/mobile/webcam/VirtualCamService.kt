package com.facefusion.mobile.webcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.facefusion.mobile.MainActivity
import com.facefusion.mobile.R

/**
 * Foreground keep-alive and remote transport service for the Virtual Webcam & Live Stream Engine.
 *
 * Compliant with Android 12–15 restrictions:
 * - Started ONLY when the user toggles the Virtual Webcam Output switch ON.
 * - Stopped immediately when the user toggles the Output switch OFF.
 * - Exposes notification action buttons (Prev, Play/Pause, Next, Turn OFF) so the user can
 *   switch playlist videos while broadcasting inside TikTok LIVE or another app.
 */
class VirtualCamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP_OUTPUT -> {
                onRemoteAction?.invoke("off")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> {
                onRemoteAction?.invoke("play_pause")
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                onRemoteAction?.invoke("next")
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                onRemoteAction?.invoke("prev")
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "LIVE OUTPUT ON • بث الفيديو نشط"
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Virtual Webcam Stream Active"
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(title, subtitle))
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val chan = NotificationChannel(
                    CHANNEL_ID,
                    "Virtual Webcam Live Output",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps the virtual webcam and live video output active during streaming"
                }
                nm.createNotificationChannel(chan)
            }
        }
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun actionPi(act: String, reqCode: Int): PendingIntent {
            val i = Intent(this, VirtualCamService::class.java).apply { action = act }
            return PendingIntent.getService(
                this,
                reqCode,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "⏮ Prev",
                    actionPi(ACTION_PREV, 101),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "⏯ Play/Pause",
                    actionPi(ACTION_PLAY_PAUSE, 102),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "⏭ Next",
                    actionPi(ACTION_NEXT, 103),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "⏹ OFF",
                    actionPi(ACTION_STOP_OUTPUT, 104),
                ).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "webcam_live_output"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_START_OR_UPDATE = "com.facefusion.mobile.webcam.START_OR_UPDATE"
        const val ACTION_STOP_OUTPUT = "com.facefusion.mobile.webcam.STOP_OUTPUT"
        const val ACTION_PLAY_PAUSE = "com.facefusion.mobile.webcam.PLAY_PAUSE"
        const val ACTION_NEXT = "com.facefusion.mobile.webcam.NEXT"
        const val ACTION_PREV = "com.facefusion.mobile.webcam.PREV"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var onRemoteAction: ((String) -> Unit)? = null

        fun startOrUpdate(ctx: Context, title: String, subtitle: String) {
            runCatching {
                val intent = Intent(ctx, VirtualCamService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_SUBTITLE, subtitle)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.stopService(Intent(ctx, VirtualCamService::class.java))
            }
        }
    }
}
