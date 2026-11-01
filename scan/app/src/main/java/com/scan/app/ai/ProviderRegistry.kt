package com.scan.app.ai

import com.scan.app.Lang

/** The list of wired-up providers. Add a class here and it appears in the UI. */
object ProviderRegistry {

    private val gemini = GeminiProvider()
    private val openai = OpenAiCompatProvider("openai", "https://api.openai.com/v1", "OpenAI", "OpenAI")
    private val openrouter = OpenAiCompatProvider("openrouter", "https://openrouter.ai/api/v1", "OpenRouter", "OpenRouter")
    private val groq = OpenAiCompatProvider("groq", "https://api.groq.com/openai/v1", "Groq", "Groq")
    private val anthropic = AnthropicProvider()
    private val custom = OpenAiCompatProvider(
        "custom",
        "https://api.openai.com/v1",
        "متوافق مع OpenAI",
        "OpenAI-compatible",
        needsBaseUrl = true
    )

    val all: List<AiProvider> = listOf(gemini, openai, openrouter, groq, anthropic, custom)

    fun byId(id: String?): AiProvider = all.firstOrNull { it.id == id } ?: custom

    fun name(id: String?, lang: Lang): String {
        val p = byId(id)
        return if (lang == Lang.AR) p.nameAr() else p.nameEn()
    }

    fun defaultBase(id: String?): String? = when (id) {
        "openai" -> "https://api.openai.com/v1"
        "openrouter" -> "https://openrouter.ai/api/v1"
        "groq" -> "https://api.groq.com/openai/v1"
        else -> null
    }
}
