package com.facefusion.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared state describing an in-progress single (non-batch) face swap, so a
 * foreground service can show a live progress notification and the user can
 * leave the app while the swap runs.
 *
 * Mirrors [BatchStatus] so the shade shows ETA + % complete for a single swap,
 * exactly the way a batch already does.
 */
object SwapStatus {
    var running by mutableStateOf(false); private set
    @Volatile var done: Int = 0; private set
    @Volatile var total: Int = 0; private set
    @Volatile var kind: String = ""; private set // "photo" | "video"
    @Volatile var etaS: Long = 0L; private set
    @Volatile var cancelled = false; private set

    fun begin(kindLabel: String) {
        running = true; done = 0; total = 0; kind = kindLabel
        etaS = 0L; cancelled = false
    }
    fun progress(doneFrames: Int, totalFrames: Int, etaSeconds: Long) {
        done = doneFrames; total = totalFrames; etaS = etaSeconds
    }
    fun cancel() { cancelled = true }
    fun end() { running = false; done = 0; total = 0; kind = ""; etaS = 0L }
}

/**
 * Keeps the process alive and shows a persistent notification while a single
 * face swap (photo or video) processes.
 *
 * Why this matters: on Android 12+, once an Activity is no longer in the
 * foreground the system is free to reclaim its process within ~1 minute unless
 * a foreground service is running. That meant a 5-minute Dimensity video swap
 * was reliably killed when the user locked the screen or switched apps, even
 * though the native worker was still on the CPU. Starting this service before
 * the swap and stopping it after the output is saved holds a foreground-service
 * slot for the duration, exactly like [BatchService] does for batch runs.
 *
 * The service owns NO work. The swap still runs in MainActivity.lifecycleScope;
 * this service only (a) promotes the process to foreground state, and (b)
 * polls [SwapStatus] every ~500 ms to refresh the notification with progress.
 */
class SwapService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            SwapStatus.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIF_ID, build())
        Thread({
            while (SwapStatus.running) {
                runCatching { Thread.sleep(500) }
                if (!SwapStatus.running) break
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
                }
            }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }, "swap-notify").start()
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SwapService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val titleRes = if (SwapStatus.kind == "photo") R.string.notif_swap_photo_title
                       else R.string.notif_swap_video_title
        val text = if (SwapStatus.total > 0) {
            val pct = (SwapStatus.done * 100) / SwapStatus.total
            val eta = if (SwapStatus.etaS > 0) {
                val m = SwapStatus.etaS / 60; val s = SwapStatus.etaS % 60
                "  •  %d:%02d".format(m, s)
            } else ""
            "${SwapStatus.done}/${SwapStatus.total}  ($pct%)$eta"
        } else getString(titleRes)
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(titleRes))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
        if (SwapStatus.total > 0) {
            b.setProgress(SwapStatus.total, SwapStatus.done, false)
        }
        b.addAction(Notification.Action.Builder(
            null as android.graphics.drawable.Icon?,
            getString(R.string.notif_cancel), cancel).build())
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, getString(R.string.notif_swap_channel),
                                     NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL = "single_swap"
        private const val NOTIF_ID = 1003
        private const val ACTION_CANCEL = "com.facefusion.mobile.CANCEL_SWAP"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SwapService::class.java)) }
        }
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SwapService::class.java)) }
        }
    }
}
