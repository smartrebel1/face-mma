package com.facefusion.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The responsible-use notice.
 *
 * ⚠ The property that matters: the acknowledgement is MANDATORY, and it is not
 * something the sensitivity control can influence. If the notice could be waived by
 * a profile field, then the profile would be the wrong place for it, and the next
 * person to add a control to the profile would not know they had made a safety
 * rule adjustable.
 */
class SafetyNoticeTest {

    /**
     * The INSTANCE fields of [GuardProfile] -- the ones a caller can set.
     *
     * ⚠ Not `declaredFields`, which also holds the companion's constants. Those
     * are `const val`s the compiler inlines; they cannot be written, and treating
     * one of them as a settable field would make this test wrong in a way that
     * looks like a bug in the profile.
     */
    private fun instanceFields() = GuardProfile::class.java.declaredFields
        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.name }

    @Test
    fun theProfileHasNoFieldThatCouldCarryConsent() {
        // The check is structural: a profile with a consent field would let a
        // caller construct a profile that skips the notice.
        val fields = instanceFields()
        assertFalse(
            "GuardProfile must not have a consent field: $fields",
            fields.any { it.contains("consent", ignoreCase = true) })
        assertFalse(
            "GuardProfile must not have an acknowledged field: $fields",
            fields.any { it.contains("acknowledg", ignoreCase = true) })
    }

    @Test
    fun theProfileHasNoFieldThatCouldDisableACheck() {
        val fields = instanceFields()
        for (forbidden in listOf("enabled", "disable", "bypass", "override",
                                 "mandatory", "threshold")) {
            assertFalse(
                "GuardProfile must not have a '$forbidden' field: $fields",
                fields.any { it.contains(forbidden, ignoreCase = true) })
        }
    }

    @Test
    fun theProfileHasOnlyTheThreeDocumentedFields() {
        // Exactly three: the dial, the revision, and the NSFW mode. The mode is a
        // PERMISSION (ON/OFF) rather than a threshold dial, but it is part of the
        // profile the gate reads and persists -- it lives here alongside the dial
        // for exactly that reason. A fourth field would be something the test does
        // not know about and that could silently become an escape.
        val fields = instanceFields().toSet()
        assertEquals(setOf("sensitivity", "policyRevision", "nsfwMode"), fields)
    }

    @Test
    fun theNoticeVersionIsPositive() {
        // A version of 0 would mean "no notice has ever been shown", and the
        // acceptance check would then never be satisfied.
        assertTrue(SafetyNotice.NOTICE_VERSION > 0)
    }

    @Test
    fun theNoticeUsesItsOwnStore() {
        // Not the profile's file, and not the theme's: the acknowledgement is a
        // fact about the past, and it must not be reachable by anything that
        // writes the sensitivity.
        assertNotEquals(GuardProfileStore.FILE, SafetyNotice.FILE)
        assertNotEquals("theme_prefs", SafetyNotice.FILE)
    }
}
