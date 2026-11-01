package com.scan.app.ai

import com.scan.app.Lang
import com.scan.app.ScanApp
import com.scan.app.data.CaptureStore
import com.scan.app.data.SelectedModel
import com.scan.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What the model could read from the header of a screenshot. */
data class DetectedAccount(
    val account: String,
    val platform: String,
    val confidence: String
)

/**
 * The "smart" part of the archive: right after a capture it reads only the header band of the
 * picture, asks the user's own model for the visible account name, and files the picture in that
 * account's folder. Nothing else about the screenshot is sent.
 */
class AutoLabeler(private val app: ScanApp) {

    private val store = CaptureStore.get(app)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Labels one capture. Skips work when the setting is off or the capture already has a name. */
    suspend fun labelOne(id: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && !app.settings.autoLabel) return@withContext false
        val record = store.byId(id) ?: return@withContext false
        if (record.source.isNotBlank() && !force) return@withContext false
        val models = app.settings.selectedModels
        if (models.isEmpty()) return@withContext false
        val band = ImageUtils.bandBase64(record.file) ?: return@withContext false
        val detected = detect(models, band) ?: return@withContext false
        if (detected.account.isBlank()) return@withContext false
        store.setLabel(id, detected.account, detected.platform)
        true
    }

    /**
     * Backfill: labels every capture that still has no account. Returns how many got a name.
     * The screenshots themselves never leave the device; only the header band is sent.
     */
    suspend fun labelMissing(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val pending = store.unlabeled()
        if (pending.isEmpty()) return 0
        var named = 0
        pending.forEachIndexed { index, record ->
            onProgress(index, pending.size)
            if (labelOne(record.id, force = true)) named++
            delay(300)
        }
        onProgress(pending.size, pending.size)
        return named
    }

    private suspend fun detect(models: List<SelectedModel>, band: String): DetectedAccount? {
        val lang = app.settings.lang
        val system = if (lang == Lang.AR) {
            "تقرأ نصًا قصيرًا من لقطة شاشة بدقة، ولا تخمّن أبدًا."
        } else {
            "You read short text from a screenshot accurately and never guess."
        }
        val prompt = Prompts.accountDetect(lang)
        models.take(3).forEach { model ->
            val saved = app.keys.byId(model.keyId) ?: return@forEach
            val provider = ProviderRegistry.byId(model.providerId)
            try {
                val text = provider.analyzeImages(
                    model = model.modelId,
                    key = saved.key,
                    baseUrl = saved.baseUrl ?: ProviderRegistry.defaultBase(model.providerId),
                    imagesJpegBase64 = listOf(band),
                    systemPrompt = system,
                    userPrompt = prompt,
                    maxTokens = 160
                )
                val parsed = parse(text)
                if (parsed != null && parsed.account.isNotBlank()) return parsed
            } catch (e: Exception) {
                // try the next model, and never block the capture on this
            }
        }
        return null
    }

    private fun parse(raw: String): DetectedAccount? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = try {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            return null
        }
        fun field(key: String): String =
            (obj[key] as? JsonPrimitive)?.content?.trim().orEmpty()

        var account = field("account")
        if (account.equals("null", true) || account == "—" || account == "-") account = ""
        account = account.removeSurrounding("\"").trim()
        var platform = field("platform")
        if (platform.equals("null", true)) platform = ""
        return DetectedAccount(account, platform.trim(), field("confidence"))
    }

    companion object {
        fun of(app: ScanApp): AutoLabeler = AutoLabeler(app)
    }
}
