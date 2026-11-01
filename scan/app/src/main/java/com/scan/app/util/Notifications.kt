package com.scan.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.scan.app.MainActivity
import com.scan.app.R
import com.scan.app.capture.LensService
import com.scan.app.ui.PreviewActivity

object Notifications {

    const val CH_LENS = "lens"
    const val CH_CAPTURE = "capture"
    const val CH_RESULT = "result"

    const val ID_LENS = 1001
    const val ID_CAPTURE = 1002
    const val ID_RESULT = 1003
    const val ID_CAPTURE_READY = 1004
    const val ID_CAPTURE_FAILED = 1005

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_LENS, ctx.getString(R.string.lens_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = "عدسة سكان العائمة"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CAPTURE, ctx.getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "نتائج التحليل", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            ctx, 10, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun lensNotification(ctx: Context, langAr: Boolean = true): Notification {
        val stop = PendingIntent.getService(
            ctx, 11,
            Intent(ctx, LensService::class.java).setAction(LensService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CH_LENS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (langAr) "العدسة تعمل" else "Lens is on")
            .setContentText(
                if (langAr) "اضغط على الدائرة لاختيار منطقة والالتقاط"
                else "Tap the bubble to pick an area and capture"
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(ctx))
            .addAction(0, if (langAr) "إيقاف" else "Stop", stop)
            .build()
    }

    fun captureNotification(ctx: Context, langAr: Boolean): Notification =
        NotificationCompat.Builder(ctx, CH_CAPTURE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (langAr) "جارٍ الالتقاط…" else "Capturing…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Safety net: if the preview activity cannot open, the user still gets the shot here. */
    fun captureReadyNotification(ctx: Context, path: String, langAr: Boolean): Notification {
        val i = Intent(ctx, PreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PreviewActivity.EXTRA_PATH, path)
        val pi = PendingIntent.getActivity(
            ctx, 12, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (langAr) "اللقطة جاهزة" else "Screenshot ready")
            .setContentText(
                if (langAr) "اضغط لفتح المعاينة والتحليل" else "Tap to open the preview and analyse"
            )
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    fun captureFailedNotification(ctx: Context, message: String, langAr: Boolean): Notification =
        NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (langAr) "تعذّر الالتقاط" else "Capture failed")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun resultNotification(ctx: Context, title: String, text: String, langAr: Boolean): Notification =
        NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
}
