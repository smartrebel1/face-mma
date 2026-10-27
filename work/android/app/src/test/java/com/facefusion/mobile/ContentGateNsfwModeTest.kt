package com.facefusion.mobile

import com.facefusion.mobile.NsfwMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ContentGate must propagate the profile's threshold down to the native
 * pipeline AND must never let NSFW Mode ON suppress any mandatory rule.
 *
 * ⚠ The policy decisions themselves are made by [DefaultProtectionPolicy], which
 * is exercised through a ProtectionRequest against a profile for every rule.
 * The ContentGate's job is to (a) compute the threshold from the profile and
 * (b) push it to the native pipeline. We test both.
 */
class ContentGateNsfwModeTest {

    private fun decide(
        profile: GuardProfile,
        score: Float,
        sampled: Int = 1,
        flagged: Int = 0,
        consent: Boolean = true,
    ): ProtectionDecision = DefaultProtectionPolicy.evaluate(
        ProtectionRequest(
            subject = R.string.gate_subject_source_image,
            score = score,
            sampled = sampled,
            flagged = flagged,
            detail = "",
            consentAcknowledged = consent,
        ),
        profile,
    )

    @Test
    fun applyingAProfileUpdatesProtectionThreshold() {
        // applyProfile must (a) install the new profile, (b) update the gate's
        // current threshold, and (c) not throw even when the native library is
        // absent (unit tests run on the JVM, not on device).
        val profile = GuardProfile.DEFAULT
        ContentGate.applyProfile(profile)
        assertEquals(
            GuardProfile.thresholdFor(profile.sensitivity, NsfwMode.OFF),
            ContentGate.protectionThreshold(),
            1e-6f,
        )
    }

    @Test
    fun applyingOnModeUpdatesProtectionThresholdToAdult() {
        ContentGate.applyProfile(GuardProfile.DEFAULT.copy(nsfwMode = NsfwMode.ON))
        assertEquals(GuardProfile.ADULT_THRESHOLD, ContentGate.protectionThreshold(), 0f)
    }

    @Test
    fun protectionThresholdReflectsMode() {
        ContentGate.applyProfile(GuardProfile.DEFAULT.copy(
            sensitivity = 1f, nsfwMode = NsfwMode.OFF))
        // Tightest threshold matches within a float ulp; binary floats do not
        // represent 0.1 exactly.
        assertEquals(GuardProfile.TIGHEST_THRESHOLD, ContentGate.protectionThreshold(), 1e-6f)

        ContentGate.applyProfile(GuardProfile.DEFAULT.copy(
            sensitivity = 0f, nsfwMode = NsfwMode.ON))
        assertEquals(GuardProfile.ADULT_THRESHOLD, ContentGate.protectionThreshold(), 0f)
    }

    @Test
    fun nanScoreStillRefusesInBothModes() {
        for (mode in NsfwMode.entries) {
            val d = decide(GuardProfile.DEFAULT.copy(nsfwMode = mode),
                           score = Float.NaN, consent = true)
            assertFalse("NaN must refuse in $mode", d.permitted)
            assertEquals(ProtectionReason.CHECK_UNAVAILABLE, d.reason)
            assertFalse("check-unavailable is mandatory", d.adjustable)
        }
    }

    @Test
    fun consentIsMandatoryInBothModes() {
        for (mode in NsfwMode.entries) {
            val d = decide(GuardProfile.DEFAULT.copy(nsfwMode = mode),
                           score = -2f, consent = false)
            assertFalse("consent missing must refuse in $mode", d.permitted)
            assertEquals(ProtectionReason.CONSENT_NOT_ACKNOWLEDGED, d.reason)
            assertFalse("consent is mandatory", d.adjustable)
        }
    }

    @Test
    fun videoRateRuleIsMandatoryInBothModes() {
        // 10% of 10 sampled frames = 1 allowed; 2 flagged is 20%.
        for (mode in NsfwMode.entries) {
            val d = decide(GuardProfile.DEFAULT.copy(nsfwMode = mode),
                           score = -2f, sampled = 10, flagged = 2, consent = true)
            assertFalse("2/10 flagged must refuse in $mode", d.permitted)
            assertEquals(ProtectionReason.VIDEO_RATE_EXCEEDED, d.reason)
            assertFalse("video rate is mandatory", d.adjustable)
        }
    }

    @Test
    fun scoreAboveThresholdIsRefusedAndAdjustableInOffMode() {
        // In OFF mode, anything above the current threshold is refused and
        // adjustable -- the user can loosen the slider.
        val p = GuardProfile.DEFAULT.copy(nsfwMode = NsfwMode.OFF)
        val t = GuardProfile.thresholdFor(p.sensitivity, p.nsfwMode)
        val d = decide(p, score = t + 0.01f, consent = true)
        assertFalse("t+eps must refuse in OFF", d.permitted)
        assertEquals(ProtectionReason.SCORE_ABOVE_THRESHOLD, d.reason)
        assertTrue("score threshold is adjustable in OFF", d.adjustable)
        assertEquals("OFF", d.diagnostics["mode"])
    }

    @Test
    fun scoreThresholdEffectivelyDisabledInOnMode() {
        // In ON mode the threshold is Float.MAX_VALUE, so there is no finite
        // "t+eps" the model can produce -- the score check never refuses. The
        // adjustable flag is still true semantically (the user can still turn
        // the mode off to re-tighten), but no finite score triggers refusal.
        val p = GuardProfile.DEFAULT.copy(nsfwMode = NsfwMode.ON)
        val t = GuardProfile.thresholdFor(p.sensitivity, p.nsfwMode)
        // Sanity: the threshold is out of reach.
        assertTrue(t > 1e9f)
        for (score in listOf(-2f, 0f, 0.25f, 1f, 3f, 100f)) {
            val d = decide(p, score = score, consent = true)
            assertTrue("score $score must permit in ON (threshold check disabled)",
                       d.permitted)
            assertEquals(ProtectionReason.ALLOWED, d.reason)
            assertEquals("ON", d.diagnostics["mode"])
        }
    }

    @Test
    fun noFiniteScoreIsRefusedByThresholdInOnMode() {
        // NSFW ON raises the threshold to Float.MAX_VALUE, so no finite score
        // (from the model's sigmoid/logit output) can exceed it. The score gate
        // therefore never refuses on score alone when ON. Mandatory rules
        // (consent, NaN score, video rate) are tested separately.
        val p = GuardProfile.DEFAULT.copy(nsfwMode = NsfwMode.ON)
        for (score in listOf(-5f, -2f, 0f, 0.25f, 1f, 3f, 10f, 1e6f)) {
            val d = decide(p, score = score, consent = true)
            assertTrue("score $score must NOT be refused by threshold alone in ON",
                       d.permitted)
            assertEquals(ProtectionReason.ALLOWED, d.reason)
        }
    }

    @Test
    fun scoreBelowThresholdIsAllowedInBothModes() {
        // For OFF, t-1 is well below any baseline so it allows.
        run {
            val p = GuardProfile.DEFAULT.copy(nsfwMode = NsfwMode.OFF)
            val t = GuardProfile.thresholdFor(p.sensitivity, p.nsfwMode)
            val d = decide(p, score = t - 1f, consent = true)
            assertTrue("t-1 must permit in OFF", d.permitted)
            assertEquals(ProtectionReason.ALLOWED, d.reason)
        }
        // For ON, every finite score is below MAX_VALUE so it allows.
        run {
            val p = GuardProfile.DEFAULT.copy(nsfwMode = NsfwMode.ON)
            for (score in listOf(-5f, 0f, 1f, 3f)) {
                val d = decide(p, score = score, consent = true)
                assertTrue("$score must permit in ON", d.permitted)
                assertEquals(ProtectionReason.ALLOWED, d.reason)
            }
        }
    }
}
