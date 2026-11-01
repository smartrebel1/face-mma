package com.scan.app

import android.app.Application
import android.content.Context
import com.scan.app.data.HistoryDb
import com.scan.app.data.KeyVault
import com.scan.app.data.SettingsStore
import com.scan.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ScanApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var db: HistoryDb
        private set
    lateinit var keys: KeyVault
        private set

    /** Lives as long as the process: used for post-capture work that must finish on its own. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        db = HistoryDb.build(this)
        keys = KeyVault(this)
        Notifications.createChannels(this)
    }

    companion object {
        @Volatile
        private var instance: ScanApp? = null

        fun get(ctx: Context): ScanApp = ctx.applicationContext as ScanApp
    }
}
