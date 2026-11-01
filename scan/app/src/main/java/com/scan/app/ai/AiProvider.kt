package com.scan.app.ai

import java.io.IOException

/** One model returned by a provider's model-list endpoint. */
data class AiModel(
    val id: String,
    val label: String = id,
    val free: Boolean = false
)

enum class AiErrorKind {
    BAD_KEY, NO_NET, RATE_LIMIT, QUOTA, UNSUPPORTED, MODEL_NOT_FOUND, SERVER, CANCELLED, UNKNOWN
}

class AiException(
    val kind: AiErrorKind,
    val detail: String = "",
    cause: Throwable? = null
) : IOException(detail, cause)

fun AiErrorKind.ar(): String = when (this) {
    AiErrorKind.BAD_KEY -> "المفتاح غير صالح أو منتهي. تحقّق منه في «الموديلات والمفاتيح»."
    AiErrorKind.NO_NET -> "لا يوجد اتصال بالإنترنت."
    AiErrorKind.RATE_LIMIT -> "تم تجاوز حد الطلبات (429). جرّب بعد قليل أو موديلًا آخر."
    AiErrorKind.QUOTA -> "انتهت حصة الحساب أو الرصيد."
    AiErrorKind.UNSUPPORTED -> "الصورة غير مدعومة أو غير مقروءة لهذا الموديل."
    AiErrorKind.MODEL_NOT_FOUND -> "هذا الموديل غير متاح لهذا المفتاح."
    AiErrorKind.SERVER -> "خطأ من مزوّد الخدمة. جرّب لاحقًا."
    AiErrorKind.CANCELLED -> "تم الإلغاء."
    AiErrorKind.UNKNOWN -> "خطأ غير متوقع."
}

fun AiErrorKind.en(): String = when (this) {
    AiErrorKind.BAD_KEY -> "The key is invalid or expired. Check it in Models & Keys."
    AiErrorKind.NO_NET -> "No internet connection."
    AiErrorKind.RATE_LIMIT -> "Rate limit hit (429). Retry shortly or use another model."
    AiErrorKind.QUOTA -> "The account quota or credit is used up."
    AiErrorKind.UNSUPPORTED -> "The image is not supported or not readable by this model."
    AiErrorKind.MODEL_NOT_FOUND -> "This model is not available for this key."
    AiErrorKind.SERVER -> "The provider returned an error. Try later."
    AiErrorKind.CANCELLED -> "Cancelled."
    AiErrorKind.UNKNOWN -> "Unexpected error."
}

/**
 * Every AI connection implements this. Adding a provider later = one new class plus one
 * entry in [ProviderRegistry]; nothing else in the app changes.
 */
interface AiProvider {

    /** Stable id used in storage: "gemini", "openai", "openrouter", "groq", "anthropic", "custom". */
    val id: String

    fun nameAr(): String
    fun nameEn(): String

    /** True when this provider needs the user to type a base URL (OpenAI-compatible custom). */
    val needsBaseUrl: Boolean get() = false

    /** Validates the key and returns image-capable models. Throws [AiException]. */
    suspend fun listModels(key: String, baseUrl: String? = null): List<AiModel>

    /** Sends the image (base64 JPEG) plus prompts and returns the model's text answer. */
    suspend fun analyzeImage(
        model: String,
        key: String,
        baseUrl: String?,
        jpegBase64: String,
        systemPrompt: String,
        userPrompt: String
    ): String
}
