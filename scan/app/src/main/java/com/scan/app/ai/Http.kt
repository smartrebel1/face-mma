package com.scan.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP layer: timeouts, error mapping to [AiException], no logging of bodies (keys). */
object Http {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).get().apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return execute(req)
    }

    fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url)
            .post(jsonBody.toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(req)
    }

    private fun execute(req: Request): String {
        try {
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful) return body
                throw mapError(res.code, body)
            }
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException(AiErrorKind.NO_NET, e.message.orEmpty(), e)
        } catch (e: Exception) {
            throw AiException(AiErrorKind.UNKNOWN, e.message.orEmpty(), e)
        }
    }

    private fun mapError(code: Int, body: String): AiException {
        val lower = body.lowercase()
        val kind = when {
            code == 401 || code == 403 -> AiErrorKind.BAD_KEY
            code == 400 && (lower.contains("api key") || lower.contains("api_key") || lower.contains("invalid_api_key")) ->
                AiErrorKind.BAD_KEY
            code == 402 -> AiErrorKind.QUOTA
            code == 429 -> AiErrorKind.RATE_LIMIT
            code == 404 -> AiErrorKind.MODEL_NOT_FOUND
            code == 400 && (lower.contains("image") || lower.contains("media") || lower.contains("mime")) ->
                AiErrorKind.UNSUPPORTED
            code == 413 -> AiErrorKind.UNSUPPORTED
            code in 500..599 -> AiErrorKind.SERVER
            code == 400 -> AiErrorKind.UNSUPPORTED
            else -> AiErrorKind.UNKNOWN
        }
        return AiException(kind, "HTTP $code: ${body.take(400)}")
    }

    /** Best-effort extraction of a human message from a provider error body. */
    fun errorMessage(body: String): String = try {
        val root: JsonObject = Json.parseToJsonElement(body).jsonObject
        val err = root["error"]
        when {
            err is JsonObject -> (err["message"] as? JsonPrimitive)?.content ?: body.take(200)
            err is JsonPrimitive -> err.content
            else -> body.take(200)
        }
    } catch (e: Exception) {
        body.take(200)
    }
}
