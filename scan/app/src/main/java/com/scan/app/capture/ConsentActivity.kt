package com.scan.app.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.scan.app.Lang
import com.scan.app.ScanApp

/**
 * A transparent activity whose only job is the per-session MediaProjection consent. Android 14
 * requires a fresh grant for every capture session; showing this activity is how the lens asks
 * for it without the user ever leaving the app they were in.
 */
class ConsentActivity : ComponentActivity() {

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rect = intent.getIntArrayExtra(EXTRA_RECT)
        if (savedInstanceState == null && !launched) {
            launched = true
            requestConsent(rect)
        }
    }

    private fun requestConsent(rect: IntArray?) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                CaptureService.startCapture(this, result.resultCode, data, rect)
            } else {
                LensController.restoreBubble()
                toast(ar("تم إلغاء إذن الالتقاط", "Capture permission denied"))
            }
            finish()
            overridePendingTransition(0, 0)
        }
        try {
            launcher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            LensController.restoreBubble()
            toast(ar("تعذّر طلب إذن الالتقاط على هذا الجهاز", "This device refused the capture consent dialog"))
            finish()
        }
    }

    private fun ar(arText: String, enText: String): String {
        val lang = ScanApp.get(this).settings.lang
        return if (lang == Lang.AR) arText else enText
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_RECT = "extra_rect"
        const val EXTRA_RETRY = "extra_retry"

        fun intentFor(ctx: Context, rect: IntArray?, retry: Boolean): Intent =
            Intent(ctx, ConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RECT, rect)
                .putExtra(EXTRA_RETRY, retry)
    }
}
