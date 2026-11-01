package com.scan.app

import android.app.Application
import android.content.Context
import com.scan.app.data.HistoryDb
import com.scan.app.data.KeyVault
import com.scan.app.data.SettingsStore
import com.scan.app.util.Notifications

class ScanApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var db: HistoryDb
        private set
    lateinit var keys: KeyVault
        private set

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
