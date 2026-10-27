package com.facefusion.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from the slider's position to the threshold the gate judges against.
 *
 * ⚠ These tests are about the CONTRACT, not about a number that happens to be right
 * today. If the mapping is ever recalibrated, these are the tests that have to be
 * read and consciously changed -- not quietly updated to whatever the code now
 * returns.
 */
class GuardProfileTest {

    // ------------------------------------------------------------------ range

    @Test
    fun sensitivityIsBoundedToItsDocumentedRange() {
        // The bounds are part of the contract, not an implementation detail: the
        // slider's endpoints, the mapping and the persistence all depend on them.
        assertEquals(0.0f, GuardProfile.MIN_SENSITIVITY, 0.0f)
        assertEquals(1.0f, GuardProfile.MAX_SENSITIVITY, 0.0f)
    }

    @Test
    fun aProfileOutsideTheRangeIsRejected() {
        // The data class refuses an out-of-range sensitivity rather than clamping it.
        // Clamping happens at the EDGES -- on load and on save -- where the input is
        // untrusted; a value built in code is a bug and should say so.
        var threw = false
        try {
            GuardProfile(sensitivity = 1.5f)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a sensitivity above the range must be rejected", threw)

        threw = false
        try {
            GuardProfile(sensitivity = -0.1f)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a sensitivity below the range must be rejected", threw)
    }

    // ------------------------------------------------------------------ mapping

    @Test
    fun theLowEndOfTheDialIsUpstreamsOwnBaseline() {
        // A fresh install must behave EXACTLY as the app did before the control
        // existed. If the default did not map to the upstream threshold, every
        // existing user's content would be judged differently the moment they
        // updated.
        assertEquals(
            ContentGate.THRESHOLD,
            GuardProfile.thresholdFor(GuardProfile.MIN_SENSITIVITY),
            1e-6f)
        assertEquals(ContentGate.THRESHOLD, GuardProfile.BASE_THRESHOLD, 1e-6f)
    }

    @Test
    fun theDefaultProfileIsTheBaseline() {
        assertEquals(0.0f, GuardProfile.DEFAULT_SENSITIVITY, 0.0f)
        assertEquals(
            ContentGate.THRESHOLD,
            GuardProfile.thresholdFor(GuardProfile.DEFAULT.sensitivity),
            1e-6f)
    }

    @Test
    fun theHighEndOfTheDialIsTheTightestThreshold() {
        assertEquals(
            GuardProfile.TIGHEST_THRESHOLD,
            GuardProfile.thresholdFor(GuardProfile.MAX_SENSITIVITY),
            1e-6f)
    }

    @Test
    fun theMappingIsMonotonicAndOneDirectional() {
        // Monotonic: more sensitivity, a lower threshold, always. A mapping that
        // wobbled would make the slider's meaning depend on where you stopped.
        var previous = Float.MAX_VALUE
        var step = 0f
        while (step <= 1.0001f) {
            val t = GuardProfile.thresholdFor(step)
            assertTrue(
                "threshold must fall as sensitivity rises (at $step)",
                t <= previous + 1e-6f)
            previous = t
            step += 0.01f
        }
    }

    @Test
    fun theDialCanNeverLoosenTheGatePastTheBaseline() {
        // The baseline is upstream's published safety margin. This app does not get
        // to give it back, at any position of the dial.
        var step = -0.5f
        while (step <= 1.5f) {
            val t = GuardProfile.thresholdFor(step)
            assertTrue(
                "threshold $t at sensitivity $step is above the baseline",
                t <= GuardProfile.BASE_THRESHOLD + 1e-6f)
            step += 0.05f
        }
    }

    @Test
    fun theMappingIsLinearAndDocumented() {
        // The mapping is on the THRESHOLD, not on the score. A score is a
        // probability-like quantity; a threshold is a cut point, and moving the cut
        // point is what "make the gate stricter" means.
        // 0.25 - s * (0.25 - 0.10)
        assertEquals(0.175f, GuardProfile.thresholdFor(0.5f), 1e-6f)
        assertEquals(0.2125f, GuardProfile.thresholdFor(0.25f), 1e-6f)
        assertEquals(0.2425f, GuardProfile.thresholdFor(0.05f), 1e-6f)
        assertEquals(0.1375f, GuardProfile.thresholdFor(0.75f), 1e-6f)
    }

    @Test
    fun theSliderPercentMapsToTheProfileSensitivity() {
        // The slider works in 0..100 because that is what a person reads off a
        // track; the profile works in 0.0..1.0 because that is what the mapping
        // wants. The conversion has to be exact in both directions.
        for (percent in 0..100) {
            val s = percent / 100f
            assertEquals(s, GuardProfile(sensitivity = s).sensitivity, 1e-6f)
        }
    }

    @Test
    fun everySliderPercentProducesItsOwnThreshold() {
        // The dial must have a measurable effect at every step, not only at the
        // ends. A mapping that quantised would mean a third of the slider's travel
        // did nothing, which is the failure mode a user cannot see and cannot
        // report.
        val seen = mutableSetOf<Int>()
        for (percent in 0..100) {
            val t = GuardProfile.thresholdFor(percent / 100f)
            seen.add(Math.round(t * 1_000_000f))
        }
        assertEquals("every percent must give a distinct threshold", 101, seen.size)
    }

    // ------------------------------------------------------------------ sanitising

    @Test
    fun outOfRangeStoredValuesAreClampedOnLoad() {
        val high = GuardProfileStore.sanitize(5f, GuardProfile.CURRENT_POLICY_REVISION)
        assertEquals(GuardProfile.MAX_SENSITIVITY, high.sensitivity, 1e-6f)

        val low = GuardProfileStore.sanitize(-5f, GuardProfile.CURRENT_POLICY_REVISION)
        assertEquals(GuardProfile.MIN_SENSITIVITY, low.sensitivity, 1e-6f)
    }

    @Test
    fun aNaNSensitivityIsTreatedAsMissingRatherThanPassedThrough() {
        // ⚠ coerceIn on NaN yields NaN, and a NaN threshold makes every comparison
        // false -- which in a gate is the one direction that must not happen
        // silently. A NaN is therefore the DEFAULT, not a NaN.
        val p = GuardProfileStore.sanitize(Float.NaN, GuardProfile.CURRENT_POLICY_REVISION)
        assertEquals(GuardProfile.DEFAULT_SENSITIVITY, p.sensitivity, 1e-6f)
        assertTrue(
            "the resulting threshold must be a real number",
            !GuardProfile.thresholdFor(p.sensitivity).isNaN())
    }

    @Test
    fun anInfiniteSensitivityIsTreatedAsMissing() {
        val p = GuardProfileStore.sanitize(Float.POSITIVE_INFINITY,
            GuardProfile.CURRENT_POLICY_REVISION)
        assertEquals(GuardProfile.DEFAULT_SENSITIVITY, p.sensitivity, 1e-6f)
    }

    @Test
    fun aNonPositivePolicyRevisionFallsBackToTheCurrentOne() {
        // A revision of 0 is not a revision. Rejecting the whole profile would leave
        // a user with no way to fix it, so it is treated as "unknown" instead.
        val p = GuardProfileStore.sanitize(0.5f, 0)
        assertEquals(GuardProfile.CURRENT_POLICY_REVISION, p.policyRevision)
        val q = GuardProfileStore.sanitize(0.5f, -3)
        assertEquals(GuardProfile.CURRENT_POLICY_REVISION, q.policyRevision)
    }

    @Test
    fun aValidStoredValueSurvivesSanitisingUnchanged() {
        val p = GuardProfileStore.sanitize(0.65f, GuardProfile.CURRENT_POLICY_REVISION)
        assertEquals(0.65f, p.sensitivity, 1e-6f)
        assertEquals(GuardProfile.CURRENT_POLICY_REVISION, p.policyRevision)
    }

    @Test
    fun sanitisingIsIdempotent() {
        // Sanitising an already-sanitised profile must not change it, or the value
        // would drift every time it was read.
        val once = GuardProfileStore.sanitize(3f, 1)
        val twice = GuardProfileStore.sanitize(once.sensitivity, once.policyRevision)
        assertEquals(once.sensitivity, twice.sensitivity, 0.0f)
        assertEquals(once.policyRevision, twice.policyRevision)
    }
}
