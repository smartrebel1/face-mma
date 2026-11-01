package com.scan.app.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The four layers the app renders as cards, plus the caution notes. */
@Serializable
data class Analysis(
    val observed: String = "",
    val classification: String = "",
    val pattern: String = "",
    val interpretation: String = "",
    val confidence: String = "",
    val limits: String = "",
    val notes: List<String> = emptyList()
) {
    fun hasAny(): Boolean =
        observed.isNotBlank() || classification.isNotBlank() || pattern.isNotBlank() ||
            interpretation.isNotBlank() || notes.isNotEmpty()
}

/**
 * Models do not always return clean JSON (fences, extra prose, slightly different keys), so the
 * parser is deliberately forgiving: it takes the outermost object it can find and flattens both
 * the nested shape we ask for and the flat shape some models prefer.
 */
object AnalysisParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val labels = mapOf(
        "text" to "النص", "account" to "الحساب", "platform" to "المنصة", "date" to "التاريخ",
        "topic" to "الموضوع", "subtopic" to "التصنيف", "tone" to "النبرة",
        "status" to "الحالة", "evidence" to "الأدلة",
        "interpretation" to "القراءة", "confidence" to "الثقة", "limits" to "الحدود",
        "summary" to "الملخص", "label" to "التصنيف", "category" to "التصنيف",
        "possible_interpretation" to "القراءة", "meaning" to "المعنى", "reading" to "القراءة",
        "limitations" to "الحدود", "caveats" to "تحفظات", "level" to "الثقة"
    )

    fun parse(raw: String): Analysis {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return Analysis()
        val jsonText = extractObject(cleaned) ?: return Analysis(observed = cleaned)
        val root = try {
            json.parseToJsonElement(jsonText) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Analysis(observed = cleaned)

        val observed = flatten(root["observed"])
        val classification = flatten(root["classification"])
        val pattern = flatten(root["pattern"])

        val inference = root["inference"] as? JsonObject
        val interpretation = if (inference != null) {
            flattenLike(inference, listOf("interpretation", "possible_interpretation", "reading", "meaning", "summary"))
        } else {
            flatten(root["inference"])
        }
        val confidence = if (inference != null) {
            flattenLike(inference, listOf("confidence", "level", "certainty"))
        } else {
            ""
        }
        val limits = if (inference != null) {
            flattenLike(inference, listOf("limits", "limitations", "caveats", "uncertainty"))
        } else {
            ""
        }

        val notes = mutableListOf<String>()
        listOf("notes", "caveats", "warnings", "verification", "missing_context").forEach { key ->
            val el = root[key] ?: return@forEach
            val text = flatten(el)
            if (text.isNotBlank()) notes += text
        }
        if (limits.isNotBlank() && inference == null) notes += limits

        val result = Analysis(
            observed = observed,
            classification = classification,
            pattern = pattern,
            interpretation = interpretation,
            confidence = confidence,
            limits = limits,
            notes = notes
        )
        return if (result.hasAny()) result else Analysis(observed = cleaned)
    }

    /** First '{' to last '}' -- survives ```json fences and stray prose. */
    private fun extractObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun flatten(el: JsonElement?): String {
        if (el == null) return ""
        return when (el) {
            is JsonPrimitive -> el.content
            is JsonArray -> el.mapNotNull { item ->
                flatten(item).takeIf { s -> s.isNotBlank() }
            }.joinToString("\n")
            is JsonObject -> el.entries.mapNotNull { (k, v) ->
                val text = flatten(v)
                if (text.isBlank() || text == "—" || text == "-") null
                else "${labels[k] ?: k}: $text"
            }.joinToString("\n")
        }
    }

    private fun flattenLike(obj: JsonObject, keys: List<String>): String {
        keys.forEach { k ->
            val el = obj[k] ?: return@forEach
            val text = flatten(el)
            if (text.isNotBlank()) return text
        }
        return ""
    }
}
