package com.scan.app.util

import com.scan.app.ScanApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Applies the "auto-delete screenshots after N days" setting: image goes, text stays. */
object Retention {

    suspend fun run(app: ScanApp) {
        val days = app.settings.autoDeleteDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val dao = app.db.history()
        withContext(Dispatchers.IO) {
            try {
                com.scan.app.data.CaptureStore.get(app).purgeOlderThan(days)
            } catch (e: Exception) {
                // never let retention crash the app
            }
            try {
                dao.olderThan(cutoff).forEach { item ->
                    val path = item.imagePath ?: return@forEach
                    File(path).delete()
                    dao.update(item.copy(imagePath = null))
                }
            } catch (e: Exception) {
                // retention is best effort, never worth a crash
            }
        }
    }
}
