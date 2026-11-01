package com.scan.app.capture

import android.annotation.SuppressLint
import android.app.Service
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
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
 * requires the projection to be driven from a foreground service: it grabs one frame, crop it,
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
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_CAPTURE,
                Notifications.captureNotification(this, langAr()),
                type
            )
        } catch (e: Exception) {
            // Without the foreground announcement the projection cannot start; say so loudly.
            fail(
                ar(
                    "تعذّر تشغيل خدمة الالتقاط. تأكد من السماح بالإشعارات ثم أعد المحاولة.",
                    "The capture service could not start. Allow notifications and try again."
                )
            )
            stopSelf()
            return START_NOT_STICKY
        }

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
            fail(
                ar(
                    "لم يصل إذن الالتقاط. أعد المحاولة من العدسة.",
                    "The capture permission was not delivered. Try again from the lens."
                )
            )
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

                // 1) Always leave a tappable notification, 2) then try to open the preview.
                //    If the activity start is blocked, the notification is the way in.
                notifyReady(file.absolutePath)
                Handler(Looper.getMainLooper()).post {
                    try {
                        PreviewActivity.start(this@CaptureService, file.absolutePath)
                    } catch (e: Exception) {
                        // fallback: the notification posted above
                    }
                }
            } catch (e: ScreenCapture.CaptureError) {
                val expired = e.kind == ScreenCapture.CaptureError.Kind.PERMISSION
                if (expired && !retried) {
                    // One-shot consent token expired: ask once more, quietly.
                    try {
                        startActivity(ConsentActivity.intentFor(this@CaptureService, rectArr, true))
                    } catch (ex: Exception) {
                        fail(
                            ar(
                                "انتهت صلاحية إذن الالتقاط. أعد المحاولة من العدسة.",
                                "Capture consent expired. Try again from the lens."
                            )
                        )
                    }
                } else {
                    when (e.kind) {
                        ScreenCapture.CaptureError.Kind.TIMEOUT -> fail(
                            ar(
                                "لم تصل صورة من النظام (مهلة). أعد المحاولة من العدسة.",
                                "No frame arrived from the system (timeout). Try again from the lens."
                            )
                        )

                        ScreenCapture.CaptureError.Kind.PERMISSION -> fail(
                            ar(
                                "انتهت صلاحية إذن الالتقاط. أعد المحاولة من العدسة.",
                                "Capture consent expired. Try again from the lens."
                            )
                        )

                        else -> fail(
                            ar("تعذّر الالتقاط: ", "Capture failed: ") + e.message.orEmpty()
                        )
                    }
                }
            } catch (e: Exception) {
                fail(ar("تعذّر الالتقاط: ", "Capture failed: ") + (e.message ?: ""))
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

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun notifyReady(path: String) {
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(this).notify(
                Notifications.ID_CAPTURE_READY,
                Notifications.captureReadyNotification(this, path, langAr())
            )
        } catch (e: Exception) {
            // notification permission revoked: the preview still opens directly
        }
    }

    @SuppressLint("MissingPermission")
    private fun fail(message: String) {
        if (canPostNotifications()) {
            try {
                NotificationManagerCompat.from(this).notify(
                    Notifications.ID_CAPTURE_FAILED,
                    Notifications.captureFailedNotification(this, message, langAr())
                )
            } catch (e: Exception) {
                // ignore
            }
        }
        toast(message)
    }

    private fun toast(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_LONG)
                    .show()
            } catch (e: Exception) {
                // ignore
            }
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
