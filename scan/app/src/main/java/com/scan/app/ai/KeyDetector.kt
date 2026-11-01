package com.scan.app.ai

/** Result of sniffing a pasted key. [providerId] is null when the format is unknown. */
data class KeyDetection(
    val providerId: String?,
    val baseUrl: String? = null,
    val noteAr: String = "",
    val noteEn: String = ""
)

/**
 * Auto-detects the provider from the key prefix, as the product spec asks:
 * AIza... Gemini | sk-or-... OpenRouter | gsk_... Groq | sk-ant-... Anthropic | sk-... OpenAI.
 */
object KeyDetector {

    fun detect(raw: String): KeyDetection {
        val k = raw.trim()
        return when {
            k.isEmpty() -> KeyDetection(null, null, "الصق المفتاح أولًا", "Paste a key first")
            k.startsWith("AIza") -> KeyDetection("gemini", null, "تم التعرف: Google Gemini", "Detected: Google Gemini")
            k.startsWith("sk-or-") -> KeyDetection("openrouter", null, "تم التعرف: OpenRouter", "Detected: OpenRouter")
            k.startsWith("gsk_") -> KeyDetection("groq", null, "تم التعرف: Groq", "Detected: Groq")
            k.startsWith("sk-ant-") -> KeyDetection("anthropic", null, "تم التعرف: Anthropic", "Detected: Anthropic")
            k.startsWith("sk-proj-") || k.startsWith("sk-") ->
                KeyDetection("openai", null, "تم التعرف: OpenAI", "Detected: OpenAI")
            else -> KeyDetection(
                null,
                null,
                "صيغة غير معروفة: اختر «متوافق مع OpenAI» وأدخل Base URL",
                "Unknown format: choose OpenAI-compatible and enter a base URL"
            )
        }
    }
}
