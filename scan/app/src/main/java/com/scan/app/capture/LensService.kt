package com.scan.app.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.scan.app.util.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The always-on part: a foreground service that owns the floating lens. Its type is
 * `specialUse` rather than `mediaProjection` on purpose -- Android 14 refuses to start a
 * mediaProjection service before the user has granted consent, and the bubble has to exist
 * *before* the first capture. The actual projection runs in [CaptureService].
 */
class LensService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_LENS,
                Notifications.lensNotification(this, langAr()),
                type
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        LensController.attach(this)
        return START_STICKY
    }

    override fun onDestroy() {
        LensController.detach()
        _running.value = false
        try {
            NotificationManagerCompat.from(this).cancel(Notifications.ID_LENS)
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    private fun langAr(): Boolean = com.scan.app.ScanApp.get(this).settings.lang == com.scan.app.Lang.AR

    companion object {
        const val ACTION_START = "com.scan.app.LENS_START"
        const val ACTION_STOP = "com.scan.app.LENS_STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(ctx: Context) {
            val i = Intent(ctx, LensService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, LensService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (e: Exception) {
                // service already gone
            }
        }
    }
}
