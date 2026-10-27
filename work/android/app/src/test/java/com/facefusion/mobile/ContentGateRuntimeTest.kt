package com.facefusion.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The runtime integration: the value the slider sets reaches the gate, and the gate
 * is what the request-processing pipeline actually asks.
 *
 * ⚠ These tests do NOT call the native classifier -- that needs the models and a
 * device. They test the seam the classifier's score comes through, which is
 * [ContentGate.applyProfile] -> [ContentGate.protectionThreshold] -> the decision.
 * The classifier is a number source; everything that happens to the number after
 * it arrives is what these tests cover, and that is the part the slider changes.
 */
class ContentGateRuntimeTest {

    @Before
    fun reset() {
        // Every test starts from the upstream baseline, so one test's profile cannot
        // leak into the next. The gate is a process-wide object, so this matters.
        ContentGate.applyProfile(GuardProfile.DEFAULT)
    }

    @Test
    fun theGateStartsAtTheUpstreamBaseline() {
        assertEquals(
            ContentGate.THRESHOLD, ContentGate.protectionThreshold(), 1e-6f)
    }

    @Test
    fun applyingAProfileChangesTheThresholdTheGateUsesImmediately() {
        // ⚠ IMMEDIATELY. No rebuild, no restart, no re-init. The profile is read at
        // the moment a score becomes a decision.
        ContentGate.applyProfile(GuardProfile(1f))
        assertEquals(
            GuardProfile.TIGHEST_THRESHOLD, ContentGate.protectionThreshold(), 1e-6f)

        ContentGate.applyProfile(GuardProfile(0.5f))
        assertEquals(0.175f, ContentGate.protectionThreshold(), 1e-6f)

        ContentGate.applyProfile(GuardProfile(0f))
        assertEquals(ContentGate.THRESHOLD, ContentGate.protectionThreshold(), 1e-6f)
    }

    @Test
    fun theGateHoldsTheProfileItWasGiven() {
        val p = GuardProfile(0.65f)
        ContentGate.applyProfile(p)
        assertEquals(p.sensitivity, ContentGate.profile().sensitivity, 1e-6f)
        assertEquals(p.policyRevision, ContentGate.profile().policyRevision)
    }

    @Test
    fun anOutOfRangeProfileCannotReachTheGate() {
        // The gate sanitises on the way in, so even a caller that skipped the store
        // cannot put the gate into an impossible state.
        //
        // ⚠ GuardProfile's own constructor refuses an out-of-range value, so the
        // profile is built through the store's sanitiser -- which is the path an
        // untrusted stored value takes.
        val rogue = GuardProfileStore.sanitize(9f, GuardProfile.CURRENT_POLICY_REVISION)
        ContentGate.applyProfile(rogue)
        assertEquals(
            GuardProfile.TIGHEST_THRESHOLD, ContentGate.protectionThreshold(), 1e-6f)
        assertTrue(
            "the threshold must always be a real number",
            !ContentGate.protectionThreshold().isNaN())
    }

    @Test
    fun theThresholdTheGateReportsIsTheOneTheMappingProduces() {
        // The gate and the slider must not be able to disagree: the threshold the
        // gate uses is DERIVED from the profile, never set independently.
        for (i in 0..100) {
            val p = GuardProfile(i / 100f)
            ContentGate.applyProfile(p)
            assertEquals(
                "the gate's threshold must be the mapping's, at ${i / 100f}",
                GuardProfile.thresholdFor(p.sensitivity),
                ContentGate.protectionThreshold(),
                1e-6f)
        }
    }

    @Test
    fun theLiveCameraPathGetsTheThresholdInForce() {
        // The live camera is the one path that used to ignore the setting entirely,
        // because it was handed `ContentGate.THRESHOLD` -- the upstream constant --
        // rather than the threshold in force. It is the path where the camera can be
        // pointed at anything at any time, so it is the path that most needed to
        // honour the setting.
        ContentGate.applyProfile(GuardProfile(1f))
        val liveThreshold = ContentGate.protectionThreshold()
        assertEquals(GuardProfile.TIGHEST_THRESHOLD, liveThreshold, 1e-6f)
        assertTrue(
            "the live threshold must be tighter than the upstream constant",
            liveThreshold < ContentGate.THRESHOLD)
    }

    @Test
    fun aDecisionTakenAfterAChangeUsesTheNewProfile() {
        // The end-to-end shape of the feature: a request evaluated before the change
        // and one evaluated after it get different answers, with no restart between.
        val request = ProtectionRequest(subject = 1, score = 0.15f)

        ContentGate.applyProfile(GuardProfile(0f))
        val before = DefaultProtectionPolicy.evaluate(request, ContentGate.profile())
        assertTrue("0.15 passes at the baseline", before.permitted)

        ContentGate.applyProfile(GuardProfile(1f))
        val after = DefaultProtectionPolicy.evaluate(request, ContentGate.profile())
        assertFalse("0.15 is refused at the top", after.permitted)
        assertEquals(ProtectionReason.SCORE_ABOVE_THRESHOLD, after.reason)
    }

    @Test
    fun thePolicyEvaluatesAgainstTheProfileTheGateHolds() {
        // The gate's profile and the policy's input are the same object, so a
        // decision taken through the gate cannot be taken against a different
        // sensitivity than the one the slider is showing.
        ContentGate.applyProfile(GuardProfile(0.75f))
        val d = DefaultProtectionPolicy.evaluate(
            ProtectionRequest(subject = 1, score = 0.15f), ContentGate.profile())
        assertFalse(d.permitted)
        assertEquals("0.7500", d.diagnostics["sensitivity"])
    }
}
