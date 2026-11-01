package com.scan.app.ai

import com.scan.app.Lang
import com.scan.app.data.KeyVault
import com.scan.app.data.SelectedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Where a long series run currently is, so the screen can show real progress. */
data class SeriesProgress(
    val done: Int,
    val total: Int,
    val note: String = ""
) {
    val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
}

/**
 * Analyses a whole folder of captures of ONE account instead of one screenshot.
 *
 * Two passes:
 *  1. every [batchSize] screenshots go to the model together and come back as a plain-text digest
 *     per capture -- this is what makes 30 captures possible without 30 images in one request;
 *  2. the digests are sent back in a single text-only turn that produces the usual four-layer
 *     JSON, so the pattern layer is grounded in every capture the user archived.
 */
class SeriesEngine(private val vault: KeyVault) {

    private val batchSize = 6
    private val maxImages = 40

    /** Images beyond [maxImages] are ignored: keeps payloads inside every provider's limits. */
    fun trim(images: List<String>): List<String> = images.take(maxImages)

    suspend fun analyzeSeries(
        models: List<SelectedModel>,
        imagesJpegBase64: List<String>,
        lang: Lang,
        mode: AnalysisMode,
        custom: String,
        label: String,
        compare: Boolean,
        fallback: Boolean,
        onProgress: (SeriesProgress) -> Unit = {}
    ): List<ModelResult> = withContext(Dispatchers.IO) {
        val shots = trim(imagesJpegBase64)
        if (models.isEmpty() || shots.isEmpty()) return@withContext emptyList()

        if (compare) {
            val usable = models
            val results = coroutineScope {
                usable.map { m ->
                    async {
                        runOne(m, shots, lang, mode, custom, label, onProgress)
                    }
                }.awaitAll()
            }
            results
        } else {
            val chain = if (fallback) models else models.take(1)
            val attempts = mutableListOf<ModelResult>()
            for (m in chain) {
                val r = runOne(m, shots, lang, mode, custom, label, onProgress)
                attempts += r
                if (r.ok) return@withContext listOf(r)
            }
            attempts
        }
    }

    private suspend fun runOne(
        sel: SelectedModel,
        shots: List<String>,
        lang: Lang,
        mode: AnalysisMode,
        custom: String,
        label: String,
        onProgress: (SeriesProgress) -> Unit
    ): ModelResult {
        val saved = vault.byId(sel.keyId)
            ?: return ModelResult(sel, null, errorKind = AiErrorKind.BAD_KEY, errorDetail = "key removed")
        val provider = ProviderRegistry.byId(sel.providerId)
        val base = saved.baseUrl ?: ProviderRegistry.defaultBase(sel.providerId)
        val system = Prompts.system(lang)

        val chunks = shots.chunked(batchSize)
        val total = chunks.size + 1
        val digests = mutableListOf<String>()
        var failedBatches = 0
        var lastError: AiException? = null

        onProgress(SeriesProgress(0, total, "batch"))

        chunks.forEachIndexed { index, chunk ->
            val prompt = Prompts.batch(index, chunks.size, chunk.size, lang, label, shots.size)
            try {
                val text = provider.analyzeImages(
                    model = sel.modelId,
                    key = saved.key,
                    baseUrl = base,
                    imagesJpegBase64 = chunk,
                    systemPrompt = system,
                    userPrompt = prompt,
                    maxTokens = 1800
                )
                digests += "=== دفعة ${index + 1} من ${chunks.size} (لقطات ${index * batchSize + 1}"
                    .plus("–${index * batchSize + chunk.size}) ===\n") + text.trim()
            } catch (e: AiException) {
                failedBatches++
                lastError = e
            } catch (e: Exception) {
                failedBatches++
                lastError = AiException(AiErrorKind.UNKNOWN, e.message ?: "")
            }
            onProgress(SeriesProgress(index + 1, total, "batch"))
            if (index < chunks.lastIndex) delay(350)
        }

        if (digests.isEmpty()) {
            return ModelResult(
                sel,
                null,
                errorKind = lastError?.kind ?: AiErrorKind.SERVER,
                errorDetail = lastError?.detail ?: "no batch succeeded"
            )
        }

        val userPrompt = Prompts.series(
            lang = lang,
            mode = mode,
            custom = custom,
            label = label,
            captureCount = shots.size,
            digests = digests.joinToString("\n\n")
        )

        // Pass 2 is text-only: the model reasons over the digests, not over raw pixels again.
        val finalText = try {
            provider.analyzeImages(
                model = sel.modelId,
                key = saved.key,
                baseUrl = base,
                imagesJpegBase64 = emptyList(),
                systemPrompt = system,
                userPrompt = userPrompt,
                maxTokens = 3200
            )
        } catch (e: AiException) {
            return ModelResult(sel, null, errorKind = e.kind, errorDetail = e.detail)
        } catch (e: Exception) {
            return ModelResult(sel, null, errorKind = AiErrorKind.UNKNOWN, errorDetail = e.message ?: "")
        }

        onProgress(SeriesProgress(total, total, "final"))

        var parsed = AnalysisParser.parse(finalText)
        val notes = parsed.notes.toMutableList()
        if (failedBatches > 0) {
            notes += if (lang == Lang.AR) {
                "تعذّر تحليل $failedBatches من ${chunks.size} دفعات (${lastError?.kind?.ar() ?: ""}); النتيجة مبنية على بقية اللقطات."
            } else {
                "$failedBatches of ${chunks.size} batches failed (${lastError?.kind?.en() ?: ""}); the result uses the remaining captures."
            }
        }
        notes += if (lang == Lang.AR) {
            "تم تحليل ${shots.size - failedBatches * batchSize.coerceAtLeast(1)} لقطة تقريبًا من أصل ${shots.size}."
        } else {
            "About ${shots.size - failedBatches * batchSize.coerceAtLeast(1)} of ${shots.size} captures were analysed."
        }
        parsed = parsed.copy(notes = notes.distinct())

        return ModelResult(sel, parsed, raw = finalText)
    }
}
