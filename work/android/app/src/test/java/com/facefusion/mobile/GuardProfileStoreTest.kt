package com.facefusion.mobile

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A [SharedPreferences] that behaves like the real one, without the Android
 * framework.
 *
 * ⚠ Why this exists rather than Robolectric: the point of these tests is that the
 * value SURVIVES A RESTART, and what "a restart" means for the store is that a
 * SECOND read -- from a second store instance over the same backing data -- sees
 * the value that was written. That is exactly what this fake models, and it
 * models it faithfully enough to catch the bug it is aimed at: a store that
 * computes the value but never writes it, or writes it under a key the reader
 * does not read.
 *
 * The in-memory map is SHARED between the two instances, which is the whole
 * point: the second instance must find what the first wrote.
 */
private class FakePrefs(
    private val backing: MutableMap<String, Any?> = mutableMapOf(),
) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = backing

    override fun getString(key: String?, defValue: String?): String? =
        backing[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (backing[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        backing[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        backing[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        backing[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        backing[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = backing.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) backing.clear()
            for ((k, v) in pending) {
                if (v == null) backing.remove(k) else backing[k] = v
            }
            pending.clear()
            clear = false
            return true
        }

        override fun apply() { commit() }
    }
}

/**
 * Helper: build a fresh FakePrefs over a SHARED backing map, so a "restart" is a
 * new FakePrefs reading the same map -- exactly the way two processes would read
 * the same preferences file.
 */
private fun prefsWith(
    pairs: List<Pair<String, Any>> = emptyList(),
): Pair<SharedPreferences, MutableMap<String, Any?>> {
    val backing = mutableMapOf<String, Any?>()
    for ((k, v) in pairs) backing[k] = v
    return FakePrefs(backing) to backing
}

/** A second view of the same backing, as if the app had restarted. */
private fun restart(backing: MutableMap<String, Any?>): SharedPreferences = FakePrefs(backing)

/**
 * The persistence of the protection setting.
 *
 * ⚠ The property under test is that the value SURVIVES A RESTART. Everything else
 * here is about making that test honest: the value has to be written somewhere,
 * read back from the same place, and clamped on the way in and out.
 *
 * ⚠ These tests use the [GuardProfileStore.load]/[GuardProfileStore.save]
 * overloads that take a [SharedPreferences] directly. The Context-accepting
 * overloads are thin wrappers around them (they just fetch the named file), so
 * exercising the SharedPreferences path exercises the same code. We use a
 * SharedPreferences fake instead of a Context fake because android.content.Context
 * is abstract with ~40 abstract methods and constructing one is not the point of
 * the test.
 */
class GuardProfileStoreTest {

    @Test
    fun theSelectedSettingSurvivesARestart() {
        val (first, backing) = prefsWith()
        GuardProfileStore.save(first, GuardProfile(0.65f))
        val restored = GuardProfileStore.load(restart(backing))
        assertEquals(0.65f, restored.sensitivity, 1e-6f)
    }

    @Test
    fun thePolicyRevisionSurvivesARestartToo() {
        val (first, backing) = prefsWith()
        GuardProfileStore.save(first, GuardProfile(0.5f, policyRevision = 3))
        val restored = GuardProfileStore.load(restart(backing))
        assertEquals(3, restored.policyRevision)
    }

    @Test
    fun theNsfwModeSurvivesARestart() {
        val (first, backing) = prefsWith()
        GuardProfileStore.save(first, GuardProfile.DEFAULT.copy(nsfwMode = NsfwMode.ON))
        val restored = GuardProfileStore.load(restart(backing))
        assertEquals(NsfwMode.ON, restored.nsfwMode)
        // Switch back to OFF round-trips too.
        GuardProfileStore.save(restart(backing), GuardProfile.DEFAULT)
        assertEquals(NsfwMode.OFF, GuardProfileStore.load(restart(backing)).nsfwMode)
    }

    @Test
    fun aFreshInstallGetsTheDefault() {
        val (fresh, _) = prefsWith()
        val p = GuardProfileStore.load(fresh)
        assertEquals(GuardProfile.DEFAULT_SENSITIVITY, p.sensitivity, 1e-6f)
        assertEquals(GuardProfile.CURRENT_POLICY_REVISION, p.policyRevision)
        assertEquals(NsfwMode.OFF, p.nsfwMode)
    }

    @Test
    fun everySliderValueSurvivesARestart() {
        for (percent in 0..100) {
            val (first, backing) = prefsWith()
            val expected = percent / 100f
            GuardProfileStore.save(first, GuardProfile(expected))
            val restored = GuardProfileStore.load(restart(backing))
            assertEquals(
                "percent $percent must survive a restart",
                expected, restored.sensitivity, 1e-6f)
        }
    }

    @Test
    fun savingClampsAnOutOfRangeValue() {
        val (first, backing) = prefsWith()
        val saved = GuardProfileStore.sanitize(9f, GuardProfile.CURRENT_POLICY_REVISION)
        GuardProfileStore.save(first, saved)
        val restored = GuardProfileStore.load(restart(backing))
        assertEquals(GuardProfile.MAX_SENSITIVITY, restored.sensitivity, 1e-6f)
    }

    @Test
    fun aCorruptedStoredValueLoadsAsTheDefaultRatherThanCrashing() {
        val (prefs, _) = prefsWith(listOf("sensitivity" to "not a number"))
        val p = GuardProfileStore.load(prefs)
        assertEquals(GuardProfile.DEFAULT_SENSITIVITY, p.sensitivity, 1e-6f)
    }

    @Test
    fun aStoredValueOfTheWrongNumericTypeLoadsAsDefault() {
        val (prefs, _) = prefsWith(listOf("sensitivity" to 42))  // Int, not Float
        val p = GuardProfileStore.load(prefs)
        assertEquals(GuardProfile.DEFAULT_SENSITIVITY, p.sensitivity, 1e-6f)
    }

    @Test
    fun aStoredValueOutsideTheRangeIsClampedOnTheWayIn() {
        val (prefs, _) = prefsWith(listOf("sensitivity" to 7f))
        val p = GuardProfileStore.load(prefs)
        assertEquals(GuardProfile.MAX_SENSITIVITY, p.sensitivity, 1e-6f)
        assertTrue(
            "the threshold must stay usable",
            !GuardProfile.thresholdFor(p.sensitivity, p.nsfwMode).isNaN())
    }

    @Test
    fun aCorruptedNsfwModeFallsBackToOff() {
        // An unknown ordinal must be read as OFF, never ON.
        val (prefs, _) = prefsWith(listOf("nsfw_mode" to 99))
        assertEquals(NsfwMode.OFF, GuardProfileStore.load(prefs).nsfwMode)
    }

    @Test
    fun theStoreUsesItsOwnNamedFile() {
        assertTrue(GuardProfileStore.FILE.isNotBlank())
        assertFalse(
            "the store's file must not be the theme's",
            GuardProfileStore.FILE == "theme_prefs")
    }
}
