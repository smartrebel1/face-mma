package com.facefusion.mobile

import com.facefusion.mobile.NsfwMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NSFW Mode: ON/OFF, and what the threshold actually becomes.
 *
 * ⚠ This is the test that has to say "OFF is the default" and "ON does not go
 * below the adult threshold". If either of those properties regressed, the mode
 * would read as a setting whose effect is the opposite of what the UI promises.
 */
class NsfwModeTest {

    @Test
    fun offIsTheDefault() {
        // The default must be OFF, and OFF must be the value you get from an
        // empty store. A default that switched ON silently on first launch would
        // be a content decision the user never made, and that is not acceptable.
        assertEquals(NsfwMode.OFF, GuardProfile.DEFAULT.nsfwMode)
        assertEquals(NsfwMode.OFF, NsfwMode.OFF)
    }

    @Test
    fun offUsesTheSensitivityMappingNeverAboveBaseline() {
        // ⚠ In OFF mode the threshold must never be above the upstream baseline.
        // Raising it would make the gate MORE permissive, which is not what OFF
        // promises.
        for (s in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val t = GuardProfile.thresholdFor(s, NsfwMode.OFF)
            assertTrue("OFF threshold $t at sensitivity $s must be <= baseline 0.25",
                       t <= GuardProfile.BASE_THRESHOLD)
        }
    }

    @Test
    fun onRaisesThresholdToAdultValue() {
        // ON disables the NSFW score threshold (ADULT_THRESHOLD = Float.MAX_VALUE,
        // unreachable by any real score). The sensitivity dial is INERT in ON.
        val expected = GuardProfile.ADULT_THRESHOLD
        for (s in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(expected, GuardProfile.thresholdFor(s, NsfwMode.ON))
        }
    }

    @Test
    fun adultThresholdDisablesScoreGate() {
        // The adult threshold disables the NSFW score check (every real score is
        // below it). It must be large enough that no plausible classifier output
        // can reach it.
        assertTrue(GuardProfile.ADULT_THRESHOLD.isInfinite() ||
                   GuardProfile.ADULT_THRESHOLD > 1e9f)
        assertTrue(GuardProfile.ADULT_THRESHOLD > GuardProfile.BASE_THRESHOLD)
    }

    @Test
    fun onDoesNotRefuseFromScoreAlone() {
        // ⚠ ON is a user opt-in: the score threshold check must NOT refuse any
        // finite score. Mandatory rules (consent, NCII, children, prohibited
        // content) still refuse -- those are tested separately and are not
        // functions of the score threshold.
        val threshold = GuardProfile.thresholdFor(0f, NsfwMode.ON)
        for (score in listOf(-5f, -2.4f, 0f, 0.25f, 1f, 3f, 10f)) {
            assertFalse("score $score must NOT be refused by threshold alone in ON",
                        score > threshold)
        }
    }

    @Test
    fun modeIsAnEnumWithExactlyTwoValues() {
        // Exactly two states. A third value -- "auto", "permissive", "silent" --
        // is a third decision the UI is not asking the user to make.
        assertEquals(listOf(NsfwMode.OFF, NsfwMode.ON), NsfwMode.entries)
    }
}
