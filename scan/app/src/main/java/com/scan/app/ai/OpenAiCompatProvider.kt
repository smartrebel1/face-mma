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

/**
 * One implementation for every OpenAI-compatible endpoint: OpenAI, OpenRouter, Groq and any
 * custom base URL the user supplies.
 */
class OpenAiCompatProvider(
    override val id: String,
    private val defaultBase: String,
    private val nameArStr: String,
    private val nameEnStr: String,
    override val needsBaseUrl: Boolean = false
) : AiProvider {

    override fun nameAr(): String = nameArStr
    override fun nameEn(): String = nameEnStr

    private fun baseOf(baseUrl: String?): String {
        val raw = baseUrl?.takeIf { it.isNotBlank() } ?: defaultBase
        return raw.trim().trimEnd('/')
    }

    private fun headers(key: String): Map<String, String> {
        val h = mutableMapOf(
            "Authorization" to "Bearer ${key.trim()}",
            "Content-Type" to "application/json"
        )
        if (id == "openrouter") {
            h["HTTP-Referer"] = "https://github.com/smartrebel1/face-mma"
            h["X-Title"] = "SCAN"
        }
        return h
    }

    override suspend fun listModels(key: String, baseUrl: String?): List<AiModel> =
        withContext(Dispatchers.IO) {
            val base = baseOf(baseUrl)
            try {
                val body = Http.get("$base/models", headers(key))
                val root = Json.parseToJsonElement(body).jsonObject
                val arr = root["data"] as? JsonArray ?: return@withContext fallback()
                data class Row(val model: AiModel, val imageOk: Boolean)
                val rows = arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val mid = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val arch = o["architecture"] as? JsonObject
                    val modalities = (arch?.get("input_modalities") as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    val pricing = o["pricing"] as? JsonObject
                    val promptPrice = (pricing?.get("prompt") as? JsonPrimitive)?.content
                    val free = mid.endsWith(":free") || promptPrice == "0" || promptPrice == "0.0" ||
                        promptPrice == "0.000000"
                    val imageOk = when {
                        modalities != null -> modalities.contains("image")
                        else -> looksVision(mid)
                    }
                    Row(AiModel(mid, mid, free), imageOk)
                }
                val knowsModalities = arr.any { it.jsonObject["architecture"] != null }
                val imageRows = if (knowsModalities) rows.filter { it.imageOk } else rows.filter { it.imageOk }
                val chosen = if (imageRows.isEmpty()) rows else imageRows
                if (chosen.isEmpty()) fallback()
                else chosen.map { it.model }.sortedWith(
                    compareByDescending<AiModel> { it.free }.thenBy { it.id }
                )
            } catch (e: AiException) {
                if (e.kind == AiErrorKind.BAD_KEY) throw e else fallback()
            } catch (e: Exception) {
                fallback()
            }
        }

    private fun looksVision(id: String): Boolean {
        val s = id.lowercase()
        val keys = listOf(
            "gpt-4o", "gpt-4.1", "gpt-4-turbo", "gpt-4-vision", "chatgpt-4o", "gpt-5",
            "o1", "o3", "o4", "llava", "pixtral", "vision", "-vl", "vl-", "llama-3.2",
            "llama-4", "qwen2-vl", "qwen2.5-vl", "internvl", "gemini", "claude", "sonnet",
            "opus", "haiku", "grok-2-vision", "grok-4", "molmo", "pixtral-large"
        )
        return keys.any { s.contains(it) }
    }

    private fun fallback(): List<AiModel> = when (id) {
        "openai" -> listOf(
            AiModel("gpt-4o", "gpt-4o"),
            AiModel("gpt-4o-mini", "gpt-4o-mini"),
            AiModel("gpt-4.1", "gpt-4.1"),
            AiModel("gpt-4.1-mini", "gpt-4.1-mini")
        )
        "openrouter" -> listOf(
            AiModel("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash (free)", true),
            AiModel("meta-llama/llama-3.2-11b-vision-instruct:free", "Llama 3.2 11B Vision (free)", true),
            AiModel("qwen/qwen2.5-vl-72b-instruct:free", "Qwen 2.5 VL 72B (free)", true),
            AiModel("google/gemini-flash-1.5", "Gemini Flash 1.5")
        )
        "groq" -> listOf(
            AiModel("meta-llama/llama-4-scout-17b-16e-instruct", "Llama 4 Scout 17B"),
            AiModel("meta-llama/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick 17B"),
            AiModel("llama-3.2-11b-vision-preview", "Llama 3.2 11B Vision")
        )
        else -> listOf(
            AiModel("gpt-4o", "gpt-4o"),
            AiModel("gpt-4o-mini", "gpt-4o-mini")
        )
    }

    override suspend fun analyzeImage(
        model: String,
        key: String,
        baseUrl: String?,
        jpegBase64: String,
        systemPrompt: String,
        userPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val base = baseOf(baseUrl)
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 2400)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userPrompt)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$jpegBase64")
                            })
                        })
                    })
                })
            })
        }
        val body = Http.postJson("$base/chat/completions", payload.toString(), headers(key))
        val root = Json.parseToJsonElement(body).jsonObject
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw AiException(AiErrorKind.SERVER, Http.errorMessage(body))
        val message = choice["message"] as? JsonObject
        val contentEl = message?.get("content")
        val text = when (contentEl) {
            is JsonPrimitive -> contentEl.content
            is JsonArray -> contentEl.mapNotNull { part ->
                val o = part as? JsonObject ?: return@mapNotNull null
                (o["text"] as? JsonPrimitive)?.content
            }.joinToString("\n")
            else -> ""
        }.trim()
        if (text.isEmpty()) {
            val err = (message?.get("refusal") as? JsonPrimitive)?.content
                ?: Http.errorMessage(body)
            throw AiException(AiErrorKind.UNSUPPORTED, err)
        }
        text
    }
}
