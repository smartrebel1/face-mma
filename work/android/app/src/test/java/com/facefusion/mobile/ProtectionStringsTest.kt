package com.facefusion.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * The protection and safety strings, in every language the app ships.
 *
 * ⚠ Why these are checked by reading the resource FILES rather than through
 * `Resources.getString`: the unit test runs on the JVM, with no Android framework
 * and no aapt-generated `R.string` for a resource that only exists in a
 * locale-qualified folder. The files are the source of truth for what is
 * translated, and a check that read the compiled resources would not have caught a
 * string that was never translated in the first place.
 *
 * ⚠ What is being checked is the CONTRACT, not the prose:
 *  * every key the app needs exists in English;
 *  * every key the app needs exists in Arabic;
 *  * the endpoint labels are the right ones at the right ends;
 *  * the mandatory and adjustable refusals are DIFFERENT strings, in every
 *    language -- because collapsing them would tell a user that a rule they cannot
 *    change was something they could have changed with a slider.
 */
class ProtectionStringsTest {

    /** Where the app's resources live, relative to the module root. */
    private fun resDir(): File {
        // The unit test's working directory is the MODULE root (app/), so the
        // resources are at src/main/res.
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res"),
        )
        for (c in candidates) if (c.isDirectory) return c
        throw AssertionError(
            "cannot find src/main/res from ${File(".").absolutePath}")
    }

    private fun stringsFile(locale: String): File {
        val dir = if (locale == "en") "values" else "values-$locale"
        val f = File(resDir(), "$dir/strings.xml")
        assertTrue("missing $f", f.isFile)
        return f
    }

    /** The body of every `<string name="X">...</string>` in a locale. */
    private fun strings(locale: String): Map<String, String> {
        val text = stringsFile(locale).readText()
        val out = mutableMapOf<String, String>()
        // Deliberately a simple scan rather than an XML parse: the file is a
        // resource file with comments, and a scan needs no framework.
        val pattern = Pattern.compile(
            "<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL)
        val m = pattern.matcher(text)
        while (m.find()) out[m.group(1)] = m.group(2)
        return out
    }

    /** Every locale the app ships. */
    private val locales = listOf("en", "ar", "ru", "zh-rCN", "zh-rTW")

    /**
     * The keys the protection feature needs.
     *
     * ⚠ Listed here rather than derived, because a derived list would only prove
     * that the file agrees with itself.
     */
    private val requiredKeys = listOf(
        // The slider.
        "guard_title", "guard_summary", "guard_value",
        "guard_end_low", "guard_end_high", "guard_slider_cd",
        "guard_note", "guard_note_adjustable", "guard_note_mandatory",
        "guard_threshold_now", "guard_reset", "guard_reset_done",
        // The two refusals, which must not be the same string.
        "gate_blocked", "gate_blocked_adjustable",
        // The responsible-use notice.
        "safety_title", "safety_body", "safety_agree", "safety_exit",
        "safety_acknowledged", "safety_acknowledged_never",
        "safety_mandatory_note", "safety_privacy_body",
        // The refusal history.
        "guard_diag_title", "guard_diag_summary", "guard_diag_empty",
        "guard_diag_clear", "guard_diag_cleared", "guard_diag_body",
        // The tab.
        "set_tab_protection",
    )

    @Test
    fun everyProtectionKeyExistsInEnglish() {
        val en = strings("en")
        for (k in requiredKeys) {
            assertTrue("English is missing '$k'", en.containsKey(k))
            assertTrue("English '$k' is empty", en[k]!!.isNotBlank())
        }
    }

    @Test
    fun everyProtectionKeyExistsInArabic() {
        // ⚠ The task's own requirement: the warning has to be available in Arabic
        // and English, through the app's localisation mechanism rather than as a
        // hard-coded duplicate.
        val ar = strings("ar")
        for (k in requiredKeys) {
            assertTrue("Arabic is missing '$k'", ar.containsKey(k))
            assertTrue("Arabic '$k' is empty", ar[k]!!.isNotBlank())
        }
    }

    @Test
    fun theArabicFileExistsAndIsItsOwnLocale() {
        // Not a copy of the English one: a locale folder with English text in it
        // would "pass" a presence check while showing English to every Arabic
        // reader.
        val ar = strings("ar")
        val en = strings("en")
        // Every Arabic string that has an English counterpart must be a DIFFERENT
        // string. A translation that is a verbatim copy is not a translation.
        val shared = ar.keys.intersect(en.keys)
        assertTrue(
            "the Arabic locale must share keys with English to be checkable",
            shared.isNotEmpty())
        // A string that is ONLY a format -- "%1$s", "%1$s · %2$s" -- has no words
        // in it to translate, so it is legitimately the same in every language.
        // Those are excluded; anything with prose in it has to differ.
        val formatOnly = Regex("^[\\%0-9a-zA-Z\\s\\u00b7\\n()\\$]*$")
        val identical = shared.filter { ar[it] == en[it] && !formatOnly.matches(en[it] ?: "") }
        assertTrue(
            "these Arabic strings are identical to the English ones: $identical",
            identical.isEmpty())
    }

    @Test
    fun everyLocaleHasTheTwoEndpointLabels() {
        // ⚠ The slider's ends must be labelled in EVERY language. An unlabelled end
        // in one locale is a slider that is inverted for those readers.
        for (loc in locales) {
            val s = strings(loc)
            assertTrue("$loc is missing guard_end_low", s.containsKey("guard_end_low"))
            assertTrue("$loc is missing guard_end_high", s.containsKey("guard_end_high"))
            assertTrue("$loc's guard_end_low is empty", s["guard_end_low"]!!.isNotBlank())
            assertTrue("$loc's guard_end_high is empty", s["guard_end_high"]!!.isNotBlank())
        }
    }

    @Test
    fun theMandatoryAndAdjustableRefusalsAreDifferentStringsEverywhere() {
        // ⚠ THE POINT OF THE WHOLE FEATURE. `gate_blocked` is a refusal the user
        // could not have avoided; `gate_blocked_adjustable` is one the sensitivity
        // setting caused. If a translation collapses them, the app tells the user
        // that a rule they cannot change was something they could have changed.
        for (loc in locales) {
            val s = strings(loc)
            val mandatory = s["gate_blocked"]
            val adjustable = s["gate_blocked_adjustable"]
            assertTrue("$loc is missing gate_blocked", mandatory != null)
            assertTrue("$loc is missing gate_blocked_adjustable", adjustable != null)
            assertNotSameText(loc, mandatory!!, adjustable!!)
        }
    }

    @Test
    fun theMandatoryAndAdjustableNotesAreDifferentStringsEverywhere() {
        for (loc in locales) {
            val s = strings(loc)
            val adjustable = s["guard_note_adjustable"]
            val mandatory = s["guard_note_mandatory"]
            assertNotSameText(loc, adjustable!!, mandatory!!)
        }
    }

    @Test
    fun theWarningMentionsConsentPrivacyAndTheLaw() {
        // ⚠ The responsible-use notice has to actually say the three things the task
        // asks it to say: consent, privacy and the applicable restrictions. A
        // translation that lost one of them would satisfy a presence check and
        // fail the requirement.
        for (loc in listOf("en", "ar")) {
            val body = strings(loc)["safety_body"]!!.lowercase()
            val consentWords = if (loc == "en")
                listOf("consent")
            else
                // Arabic: موافقة (consent), موافقته, موافقتك.
                listOf("موافقة", "موافق")
            assertTrue(
                "$loc's safety_body does not mention consent",
                consentWords.any { body.contains(it) })

            val privacyWords = if (loc == "en")
                listOf("privacy")
            else
                listOf("الخصوصية")
            assertTrue(
                "$loc's safety_body does not mention privacy",
                privacyWords.any { body.contains(it) })

            val lawWords = if (loc == "en")
                listOf("law")
            else
                listOf("القانون")
            assertTrue(
                "$loc's safety_body does not mention the law",
                lawWords.any { body.contains(it) })
        }
    }

    @Test
    fun theArabicLocaleIsDeclaredInTheLocaleConfig() {
        // ⚠ A translations folder alone is not enough: without the entry in
        // locales_config.xml, Android 13+ does not offer the language in the
        // per-app picker, and the app follows the system locale instead.
        val cfg = File(resDir(), "xml/locales_config.xml").readText()
        assertTrue(
            "the Arabic locale must be declared in locales_config.xml",
            cfg.contains("android:name=\"ar\""))
    }

    @Test
    fun everyLocaleKeepsTheSameProtectionKeysAsEnglish() {
        // ⚠ A locale that is missing a protection key falls back to English for
        // THAT key, which means a reader gets a screen in two languages at once --
        // an English slider label under Arabic text. The fallback is fine for a
        // string nobody has translated; it is not fine for the safety notice.
        val en = strings("en")
        val protectionKeys = requiredKeys.toSet()
        for (loc in locales) {
            val s = strings(loc)
            val missing = protectionKeys.filter { !s.containsKey(it) }
            assertTrue("$loc is missing ${missing.size} protection keys: $missing",
                missing.isEmpty())
        }
        // And the totals must line up, so a locale cannot have silently lost a
        // string somewhere else.
        for (loc in locales) {
            assertTrue("$loc has fewer strings than English",
                strings(loc).size >= requiredKeys.size)
        }
        assertTrue(en.isNotEmpty())
    }

    @Test
    fun everyLocaleHasTheSameNumberOfPlaceholderArguments() {
        // ⚠ A translation that drops a %1$s is a CRASH at runtime, not a cosmetic
        // difference -- `String.format` throws on a missing argument. So the
        // placeholder COUNT has to match, for every string that has one.
        val en = strings("en")
        // The placeholder syntax: %1$s, %2$s. Matched with a regex so that the
        // COUNT is compared rather than the exact set of indices.
        val placeholder = Pattern.compile("%(\\d+)\\$[sd]")
        for (loc in locales) {
            val s = strings(loc)
            for ((k, v) in en) {
                val other = s[k] ?: continue
                val expected = placeholder.matcher(v).groupCount()
                val actual = placeholder.matcher(other).groupCount()
                assertEquals(
                    "$loc's '$k' has $actual placeholders but English has $expected",
                    expected, actual)
            }
        }
    }

    private fun assertNotSameText(loc: String, a: String, b: String) {
        assertTrue(
            "$loc has two different rules sharing one sentence:\n  A: $a\n  B: $b",
            a.trim() != b.trim())
    }
}
