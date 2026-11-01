package com.scan.app.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.scan.app.util.Notifications

/** Foreground service that owns the floating lens bubble. */
class LensService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, Notifications.ID_LENS, Notifications.lensNotification(this), type)
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.scan.app.LENS_START"
        const val ACTION_STOP = "com.scan.app.LENS_STOP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, LensService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LensService::class.java).setAction(ACTION_STOP))
        }
    }
}
