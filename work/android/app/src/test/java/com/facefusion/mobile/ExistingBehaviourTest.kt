package com.facefusion.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What must NOT have changed.
 *
 * ⚠ A feature that adds a protection control has to leave everything else alone.
 * These tests pin the behaviours that existed before the control did, because the
 * control touched the one place they all lived -- the gate -- and a change there is
 * exactly the kind that breaks something nobody was looking at.
 */
class ExistingBehaviourTest {

    @Test
    fun theUpstreamThresholdIsUnchanged() {
        // Upstream FaceFusion's `content_analyser.py` value. This number is in the
        // upstream source and in this project's own documentation, and it must not
        // move: the control TIGHTENS the gate, it does not redefine it.
        assertEquals(0.25f, ContentGate.THRESHOLD, 0.0f)
    }

    @Test
    fun theGateAndThePolicyAgreeAboutTheBaseline() {
        // The baseline is written in two places -- the gate's own constant and the
        // profile's -- and they must be the same number. A test is cheaper than
        // remembering to update both.
        assertEquals(ContentGate.THRESHOLD, GuardProfile.BASE_THRESHOLD, 0.0f)
    }

    @Test
    fun theGateAndThePolicyAgreeAboutTheVideoAllowance() {
        assertEquals(ContentGate.VIDEO_RATE_PERCENT, GuardProfile.VIDEO_RATE_PERCENT, 0.0)
    }

    @Test
    fun theVideoAllowanceIsUnchanged() {
        // Upstream's `analyse_video` refuses above 10% flagged.
        assertEquals(10.0, ContentGate.VIDEO_RATE_PERCENT, 0.0)
    }

    @Test
    fun theQuantisationBiasIsUnchanged() {
        // The measured W8A16 bias. It is reported, never subtracted, and the
        // control must not have started compensating for it.
        assertEquals(0.087f, ContentGate.QUANTISED_BIAS, 0.0f)
    }

    @Test
    fun theSampleIntervalIsUnchanged() {
        // One sample per second, as `analyse_video` does it.
        assertEquals(1_000_000L, ContentGate.SAMPLE_INTERVAL_US)
    }

    @Test
    fun theVerdictKindsAreUnchanged() {
        // ALLOW, BLOCK, ERROR -- and in particular ERROR is still a distinct
        // verdict, not folded into BLOCK.
        assertEquals(3, ContentGate.Verdict.entries.size)
        assertNotEquals(ContentGate.Verdict.ALLOW, ContentGate.Verdict.ERROR)
        assertNotEquals(ContentGate.Verdict.BLOCK, ContentGate.Verdict.ERROR)
    }

    @Test
    fun theVerdictHelpersStillBehaveAsBefore() {
        // `permitted` is ALLOW and nothing else, and `refused` is BLOCK and nothing
        // else. A check that could not run is neither, and every caller asks
        // `permitted`.
        val allow = ContentGate.Result(ContentGate.Verdict.ALLOW, 0.1f)
        val block = ContentGate.Result(ContentGate.Verdict.BLOCK, 0.9f)
        val error = ContentGate.Result(ContentGate.Verdict.ERROR, Float.NaN)

        assertTrue(allow.permitted)
        assertFalse(allow.refused)
        assertFalse(block.permitted)
        assertTrue(block.refused)
        assertFalse(error.permitted)
        assertFalse(error.refused)
    }

    @Test
    fun theLiveEngineStillTakesAThresholdRatherThanKnowsAboutAGate() {
        // The live engine takes a NUMBER and knows nothing about a gate -- that is
        // what lets the `dev` line delete ContentGate.kt and still compile. The
        // control must not have made the engine depend on the policy layer.
        val field = LiveEngine::class.java.getDeclaredField("gateThreshold")
        assertEquals(Float::class.java, field.type)
    }

    @Test
    fun theLiveEngineDefaultThresholdIsStillNotANumber() {
        // NaN, not a negative number: gate scores are routinely negative, so a
        // negative sentinel would disable the gate for exactly the low threshold
        // someone sets while TESTING that it still blocks.
        //
        // ⚠ Read off a FRESH instance rather than a static field: the default lives
        // on the instance, and a static read would be a null dereference.
        val engine = LiveEngine()
        val field = LiveEngine::class.java.getDeclaredField("gateThreshold")
        field.isAccessible = true
        val value = field.get(engine) as Float
        assertTrue("the default threshold must be NaN, was $value", value.isNaN())
    }

    @Test
    fun theSwapOptionsAreUnaffected() {
        // The protection control must not have become a pipeline option: options
        // are saved with the swap and restored with it, and a safety setting that
        // travelled with a saved swap would be a safety setting that could be
        // overridden by loading an old file.
        val opts = SwapOptions()
        assertNotEquals(SwapOptions::class.java.declaredFields.size, 0)
        // The control is not one of them.
        val fields = SwapOptions::class.java.declaredFields.map { it.name }
        for (forbidden in listOf("protection", "sensitivity", "guard", "threshold")) {
            assertFalse(
                "SwapOptions must not carry a '$forbidden' field: $fields",
                fields.any { it.contains(forbidden, ignoreCase = true) })
        }
    }

    @Test
    fun thePolicyIsTheOnlyEngineThereIs() {
        // One implementation, used by every path. A second engine is a second set
        // of rules, and two rule sets drift.
        assertTrue(
            "the default policy must implement the contract",
            DefaultProtectionPolicy is ProtectionPolicy)
    }
}
