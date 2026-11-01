package com.scan.app.ai

import com.scan.app.data.KeyVault
import com.scan.app.data.SelectedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** One model's attempt: either an [analysis] or a mapped [errorKind]. */
data class ModelResult(
    val sel: SelectedModel,
    val analysis: Analysis?,
    val raw: String = "",
    val errorKind: AiErrorKind? = null,
    val errorDetail: String = ""
) {
    val ok: Boolean get() = analysis != null
}

/**
 * Runs the chosen models. Single mode walks the list in order (that is the fallback chain);
 * compare mode runs everything at once and shows each answer in its own tab.
 */
class AnalysisEngine(private val vault: KeyVault) {

    private suspend fun callOne(
        sel: SelectedModel,
        jpegBase64: String,
        system: String,
        user: String
    ): ModelResult {
        val saved = vault.byId(sel.keyId)
            ?: return ModelResult(sel, null, errorKind = AiErrorKind.BAD_KEY, errorDetail = "key removed")
        val provider = ProviderRegistry.byId(sel.providerId)
        return try {
            val text = provider.analyzeImage(
                model = sel.modelId,
                key = saved.key,
                baseUrl = saved.baseUrl ?: sel.baseUrlHint(),
                jpegBase64 = jpegBase64,
                systemPrompt = system,
                userPrompt = user
            )
            val parsed = AnalysisParser.parse(text)
            ModelResult(sel, parsed, raw = text)
        } catch (e: AiException) {
            ModelResult(sel, null, errorKind = e.kind, errorDetail = e.detail)
        } catch (e: Exception) {
            ModelResult(sel, null, errorKind = AiErrorKind.UNKNOWN, errorDetail = e.message ?: "")
        }
    }

    private fun SelectedModel.baseUrlHint(): String? = ProviderRegistry.defaultBase(providerId)

    /**
     * Single mode. Returns the first success; if nothing succeeds, returns every attempt so the
     * UI can show what went wrong.
     */
    suspend fun singleWithFallback(
        models: List<SelectedModel>,
        jpegBase64: String,
        system: String,
        user: String,
        fallback: Boolean,
        onAttempt: (SelectedModel) -> Unit = {}
    ): List<ModelResult> = withContext(Dispatchers.IO) {
        val list = if (fallback) models else models.take(1)
        val attempts = mutableListOf<ModelResult>()
        if (list.isEmpty()) return@withContext attempts
        for (m in list) {
            onAttempt(m)
            val r = callOne(m, jpegBase64, system, user)
            attempts += r
            if (r.ok) return@withContext listOf(r)
        }
        attempts
    }

    /** Compare mode: every model at once, no fallback (a failed tab shows its own error). */
    suspend fun compare(
        models: List<SelectedModel>,
        jpegBase64: String,
        system: String,
        user: String,
        onAttempt: (SelectedModel) -> Unit = {}
    ): List<ModelResult> = withContext(Dispatchers.IO) {
        if (models.isEmpty()) return@withContext emptyList()
        models.forEach { onAttempt(it) }
        coroutineScope {
            models.map { m ->
                async { callOne(m, jpegBase64, system, user) }
            }.awaitAll()
        }
    }
}
