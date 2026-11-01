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

/** Anthropic -- messages API, base64 image source block. */
class AnthropicProvider : AiProvider {

    override val id: String = "anthropic"
    override fun nameAr(): String = "Anthropic Claude"
    override fun nameEn(): String = "Anthropic Claude"

    private val base = "https://api.anthropic.com/v1"
    private val version = "2023-06-01"

    private fun headers(key: String) = mapOf(
        "x-api-key" to key.trim(),
        "anthropic-version" to version,
        "content-type" to "application/json"
    )

    override suspend fun listModels(key: String, baseUrl: String?): List<AiModel> =
        withContext(Dispatchers.IO) {
            try {
                val body = Http.get("$base/models?limit=100", headers(key))
                val root = Json.parseToJsonElement(body).jsonObject
                val arr = root["data"] as? JsonArray ?: return@withContext fallback()
                val out = arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val mid = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val label = (o["display_name"] as? JsonPrimitive)?.content ?: mid
                    AiModel(id = mid, label = label)
                }
                if (out.isEmpty()) fallback() else out
            } catch (e: AiException) {
                if (e.kind == AiErrorKind.BAD_KEY) throw e else fallback()
            } catch (e: Exception) {
                fallback()
            }
        }

    private fun fallback(): List<AiModel> = listOf(
        AiModel("claude-sonnet-4-20250514", "Claude Sonnet 4"),
        AiModel("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet"),
        AiModel("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet"),
        AiModel("claude-3-5-haiku-latest", "Claude 3.5 Haiku")
    )

    override suspend fun analyzeImage(
        model: String,
        key: String,
        baseUrl: String?,
        jpegBase64: String,
        systemPrompt: String,
        userPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 2400)
            put("temperature", 0.2)
            put("system", systemPrompt)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", jpegBase64)
                            })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userPrompt)
                        })
                    })
                })
            })
        }
        val body = Http.postJson("$base/messages", payload.toString(), headers(key))
        val root = Json.parseToJsonElement(body).jsonObject
        val content = root["content"] as? JsonArray ?: throw AiException(AiErrorKind.SERVER, Http.errorMessage(body))
        val text = content.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.content == "text") (o["text"] as? JsonPrimitive)?.content else null
        }.joinToString("\n").trim()
        if (text.isEmpty()) throw AiException(AiErrorKind.SERVER, "empty answer (stop=${(root["stop_reason"] as? JsonPrimitive)?.content})")
        text
    }
}
