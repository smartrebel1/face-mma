package com.scan.app.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.scan.app.Lang
import com.scan.app.ScanApp
import com.scan.app.ui.PreviewActivity
import com.scan.app.util.ImageUtils
import com.scan.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Short-lived foreground service of type mediaProjection. It exists only because Android
 * requires the projection to be driven from a foreground service: it grabs one frame, crops it,
 * pushes it to the preview and stops.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_CAPTURE,
            Notifications.captureNotification(this, langAr()),
            type
        )

        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        val rectArr = intent?.getIntArrayExtra(EXTRA_RECT)
        val retried = intent?.getBooleanExtra(EXTRA_RETRIED, false) ?: false
        val rect = if (rectArr != null && rectArr.size == 4) {
            Rect(rectArr[0], rectArr[1], rectArr[2], rectArr[3])
        } else {
            null
        }

        if (data == null) {
            LensController.restoreBubble()
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                // The overlay was removed before the consent dialog; give the compositor a
                // couple of frames so our own window is really gone from the capture.
                delay(320)
                val bmp = ScreenCapture(this@CaptureService).capture(resultCode, data, rect)
                val dir = File(filesDir, "shots").apply { mkdirs() }
                val file = File(dir, "shot_${System.currentTimeMillis()}.png")
                if (!ImageUtils.savePng(bmp, file)) throw IllegalStateException("cannot write screenshot")
                bmp.recycle()
                Handler(Looper.getMainLooper()).post {
                    PreviewActivity.start(this@CaptureService, file.absolutePath)
                }
            } catch (e: ScreenCapture.CaptureError) {
                if (e.kind == ScreenCapture.CaptureError.Kind.PERMISSION && !retried) {
                    // Expired or one-shot token: ask once more, silently.
                    val again = ConsentActivity.intentFor(this@CaptureService, rectArr, true)
                    again.putExtra(ConsentActivity.EXTRA_RETRY, true)
                    try {
                        startActivity(again)
                    } catch (ex: Exception) {
                        toast(ar("انتهت صلاحية إذن الالتقاط، أعد المحاولة", "Capture consent expired, try again"))
                    }
                } else {
                    toast(
                        ar(
                            "تعذّر الالتقاط: أعد المحاولة من العدسة",
                            "Capture failed: try again from the lens"
                        )
                    )
                }
            } catch (e: Exception) {
                toast(ar("تعذّر الالتقاط", "Capture failed"))
            } finally {
                LensController.restoreBubble()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun langAr(): Boolean = ScanApp.get(this).settings.lang == Lang.AR

    private fun ar(arText: String, enText: String): String = if (langAr()) arText else enText

    private fun toast(text: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_RECT = "rect"
        private const val EXTRA_RETRIED = "retried"

        fun startCapture(
            ctx: Context,
            resultCode: Int,
            data: Intent,
            rect: IntArray?,
            retried: Boolean = false
        ) {
            val i = Intent(ctx, CaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_RECT, rect)
                .putExtra(EXTRA_RETRIED, retried)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
