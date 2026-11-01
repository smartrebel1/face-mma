package com.scan.app.data

import android.content.Context
import android.content.SharedPreferences
import com.scan.app.Lang
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A model the user ticked in Models & Keys, bound to the key that can call it. */
@Serializable
data class SelectedModel(
    val providerId: String,
    val keyId: String,
    val modelId: String,
    val label: String,
    val free: Boolean = false
)

/** Persisted, non-secret preferences. Keys live encrypted in [KeyVault]. */
class SettingsStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("scan_settings", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var lang: Lang
        get() = if (sp.getString("lang", "ar") == "en") Lang.EN else Lang.AR
        set(v) = sp.edit().putString("lang", if (v == Lang.EN) "en" else "ar").apply()

    /** Id of the default analysis mode, see [com.scan.app.ai.AnalysisMode]. */
    var defaultMode: String
        get() = sp.getString("default_mode", "general") ?: "general"
        set(v) = sp.edit().putString("default_mode", v).apply()

    /** "single" or "compare". */
    var selectionMode: String
        get() = sp.getString("selection_mode", "single") ?: "single"
        set(v) = sp.edit().putString("selection_mode", v).apply()

    var fallbackEnabled: Boolean
        get() = sp.getBoolean("fallback", true)
        set(v) = sp.edit().putBoolean("fallback", v).apply()

    var autoDeleteDays: Int
        get() = sp.getInt("auto_delete_days", 0)
        set(v) = sp.edit().putInt("auto_delete_days", v).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var customInstruction: String
        get() = sp.getString("custom_instruction", "") ?: ""
        set(v) = sp.edit().putString("custom_instruction", v).apply()

    /** Last account/source label, so the next capture of the same person is grouped automatically. */
    var lastSource: String
        get() = sp.getString("last_source", "") ?: ""
        set(v) = sp.edit().putString("last_source", v).apply()

    var lastModeUsed: String
        get() = sp.getString("last_mode", defaultMode) ?: defaultMode
        set(v) = sp.edit().putString("last_mode", v).apply()

    var selectedModels: List<SelectedModel>
        get() = try {
            val raw = sp.getString("selected_models", null) ?: return emptyList()
            json.decodeFromString(ListSerializer(SelectedModel.serializer()), raw)
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = sp.edit()
            .putString("selected_models", json.encodeToString(ListSerializer(SelectedModel.serializer()), v))
            .apply()

    fun clear() {
        sp.edit().clear().apply()
    }
}
