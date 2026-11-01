package com.scan.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** One saved API key. Never logged, never backed up, masked in the UI. */
@Serializable
data class SavedKey(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val key: String,
    val baseUrl: String? = null,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val modelCount: Int = 0
) {
    fun masked(): String = when {
        key.length <= 10 -> key.take(2) + "…" + key.takeLast(2)
        else -> key.take(6) + "…" + key.takeLast(4)
    }
}

/**
 * Encrypted key storage. Uses EncryptedSharedPreferences backed by the Android keystore.
 * If the keystore is unavailable or has been invalidated (device restore, lock change on old
 * devices) we fall back to app-private preferences rather than crashing the app -- the data is
 * still inside the app sandbox and excluded from backup.
 */
class KeyVault(context: Context) {

    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs: SharedPreferences by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "scan_keys_enc",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        app.getSharedPreferences("scan_keys_plain", Context.MODE_PRIVATE)
    }

    fun all(): List<SavedKey> = try {
        val raw = prefs.getString(KEY_LIST, null)
        if (raw == null) emptyList()
        else json.decodeFromString(ListSerializer(SavedKey.serializer()), raw)
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<SavedKey>) {
        prefs.edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(SavedKey.serializer()), list))
            .apply()
    }

    fun add(key: SavedKey): SavedKey {
        val list = all().filterNot { it.providerId == key.providerId && it.key == key.key } + key
        save(list)
        return key
    }

    fun update(key: SavedKey) {
        save(all().map { if (it.id == key.id) key else it })
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    fun byId(id: String): SavedKey? = all().firstOrNull { it.id == id }

    fun byProvider(providerId: String): List<SavedKey> = all().filter { it.providerId == providerId }

    fun clear() = save(emptyList())

    private companion object {
        const val KEY_LIST = "vault"
    }
}
