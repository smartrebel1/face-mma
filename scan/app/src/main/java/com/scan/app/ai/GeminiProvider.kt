package com.scan.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Google Gemini -- generateContent with an inline image part. */
class GeminiProvider : AiProvider {

    override val id: String = "gemini"
    override fun nameAr(): String = "Google Gemini"
    override fun nameEn(): String = "Google Gemini"

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    override suspend fun listModels(key: String, baseUrl: String?): List<AiModel> =
        withContext(Dispatchers.IO) {
            try {
                val body = Http.get("$base/models?pageSize=1000&key=${key.trim()}")
                val root = Json.parseToJsonElement(body).jsonObject
                val arr = root["models"] as? JsonArray ?: return@withContext fallback()
                val out = arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val methods = (o["supportedGenerationMethods"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    if (!methods.contains("generateContent")) return@mapNotNull null
                    val mid = name.removePrefix("models/")
                    val skip = listOf("embedding", "aqa", "imagen", "veo", "tts", "gemma", "learnlm", "image-generation")
                    if (skip.any { mid.lowercase().contains(it) }) return@mapNotNull null
                    val label = (o["displayName"] as? JsonPrimitive)?.content ?: mid
                    AiModel(id = mid, label = label, free = false)
                }.sortedByDescending { it.id }
                if (out.isEmpty()) fallback() else out
            } catch (e: AiException) {
                if (e.kind == AiErrorKind.BAD_KEY) throw e else fallback()
            } catch (e: Exception) {
                fallback()
            }
        }

    private fun fallback(): List<AiModel> = listOf(
        AiModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
        AiModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
        AiModel("gemini-2.0-flash", "Gemini 2.0 Flash"),
        AiModel("gemini-1.5-flash", "Gemini 1.5 Flash"),
        AiModel("gemini-1.5-pro", "Gemini 1.5 Pro")
    )

    override suspend fun analyzeImages(
        model: String,
        key: String,
        baseUrl: String?,
        imagesJpegBase64: List<String>,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", systemPrompt) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", userPrompt) })
                        // One image part per capture: the model sees the whole series at once.
                        imagesJpegBase64.forEach { b64 ->
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "image/jpeg")
                                    put("data", b64)
                                })
                            })
                        }
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.2)
                put("maxOutputTokens", maxTokens)
            })
        }
        val body = Http.postJson(
            "$base/models/$model:generateContent?key=${key.trim()}",
            payload.toString()
        )
        val root = Json.parseToJsonElement(body).jsonObject

        (root["promptFeedback"] as? JsonObject)
            ?.let { fb -> (fb["blockReason"] as? JsonPrimitive)?.content }
            ?.let { throw AiException(AiErrorKind.UNSUPPORTED, "blocked: $it") }

        val cand = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw AiException(AiErrorKind.SERVER, Http.errorMessage(body))
        val finish = (cand["finishReason"] as? JsonPrimitive)?.content
        if (finish == "SAFETY" || finish == "PROHIBITED_CONTENT") {
            throw AiException(AiErrorKind.UNSUPPORTED, "finishReason=$finish")
        }
        val parts = ((cand["content"] as? JsonObject)?.get("parts") as? JsonArray) ?: JsonArray(emptyList())
        val text = parts.mapNotNull { (it as? JsonObject)?.let { o -> (o["text"] as? JsonPrimitive)?.content } }
            .joinToString("\n").trim()
        if (text.isEmpty()) throw AiException(AiErrorKind.SERVER, "empty answer (finish=$finish)")
        text
    }
}
