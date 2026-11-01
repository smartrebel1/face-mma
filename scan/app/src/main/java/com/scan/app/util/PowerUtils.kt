package com.scan.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Battery-optimisation nagging is only shown on the vendors that actually kill overlays. */
object PowerUtils {

    private val pickyVendors = listOf(
        "vivo", "iqoo", "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus",
        "huawei", "honor", "infinix", "tecno", "meizu", "samsung"
    )

    fun needsBatteryHint(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return pickyVendors.any { m.contains(it) }
    }

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBattery(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ex: Exception) {
                // give up quietly
            }
        }
    }
}
