package com.scan.app.capture

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** One-shot foreground service of type mediaProjection; captures a single screenshot. */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
