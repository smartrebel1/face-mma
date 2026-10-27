package com.facefusion.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protection policy: what it decides, and -- just as importantly -- which of its
 * rules the user is allowed to move.
 *
 * ⚠ The tests in this file are the ones that matter most in this whole feature. A
 * slider that can turn a safety check off is worse than no slider, and the only
 * thing standing between the slider and the safety check is the split between the
 * ADJUSTABLE rules and the MANDATORY ones. So every mandatory rule is tested at
 * every sensitivity, not at the default.
 */
class ProtectionPolicyTest {

    private val policy: ProtectionPolicy = DefaultProtectionPolicy

    /** A request for a still, with a given score. */
    private fun still(score: Float, consent: Boolean = true) = ProtectionRequest(
        subject = 1,
        score = score,
        consentAcknowledged = consent,
    )

    // ------------------------------------------------------------------ adjustable

    @Test
    fun aLowScoreIsAllowedAtEverySensitivity() {
        for (i in 0..100) {
            val profile = GuardProfile(i / 100f)
            val d = policy.evaluate(still(0.01f), profile)
            assertTrue("score 0.01 must pass at sensitivity ${i / 100f}",
                d.permitted)
            assertEquals(ProtectionReason.ALLOWED, d.reason)
        }
    }

    @Test
    fun raisingTheSensitivityRefusesMoreMaterial() {
        // The MEASURABLE EFFECT. A score between the tightest and the loosest
        // threshold is the one that changes answer as the dial moves.
        val score = 0.15f
        // At the baseline the threshold is 0.25, so 0.15 passes.
        assertTrue(policy.evaluate(still(score), GuardProfile(0f)).permitted)
        // At the top the threshold is 0.10, so 0.15 is refused.
        assertFalse(policy.evaluate(still(score), GuardProfile(1f)).permitted)
    }

    @Test
    fun everySensitivityChangesTheAnswerForSomeScore() {
        // For every pair of sensitivities there is at least one score that the two
        // answer differently about. If that were not true, some part of the slider's
        // travel would be inert.
        for (i in 0..100 step 5) {
            for (j in (i + 5)..100 step 5) {
                val lowT = GuardProfile.thresholdFor(i / 100f)
                val highT = GuardProfile.thresholdFor(j / 100f)
                assertTrue(
                    "sensitivities $i and $j must have different thresholds",
                    lowT > highT)
                val between = (lowT + highT) / 2f
                assertTrue(
                    "score $between must pass at $i",
                    policy.evaluate(still(between), GuardProfile(i / 100f)).permitted)
                assertFalse(
                    "score $between must be refused at $j",
                    policy.evaluate(still(between), GuardProfile(j / 100f)).permitted)
            }
        }
    }

    @Test
    fun theRefusalNamesTheAdjustableRuleAndSaysSo() {
        val d = policy.evaluate(still(0.9f), GuardProfile(1f))
        assertFalse(d.permitted)
        assertEquals(ProtectionReason.SCORE_ABOVE_THRESHOLD, d.reason)
        assertEquals(DefaultProtectionPolicy.RULE_SCORE_THRESHOLD, d.ruleId)
        // ⚠ `adjustable` is what lets the UI say "the sensitivity setting caused
        // this" as opposed to "a rule you cannot change caused this".
        assertTrue("an over-threshold score is adjustable", d.adjustable)
    }

    @Test
    fun theRefusalCarriesTheNumbersNeededToExplainIt() {
        val d = policy.evaluate(still(0.30f), GuardProfile(0.25f))
        assertFalse(d.permitted)
        // The score, the threshold it was judged against, the sensitivity and the
        // revision. A refusal that cannot be explained cannot be calibrated.
        assertTrue(d.diagnostics.containsKey("score"))
        assertTrue(d.diagnostics.containsKey("threshold"))
        assertTrue(d.diagnostics.containsKey("sensitivity"))
        assertTrue(d.diagnostics.containsKey("revision"))
    }

    @Test
    fun theDiagnosticsCarryNoMedia() {
        // The diagnostics are what gets written to disk and attached to a bug report.
        // They must be numbers and identifiers, and nothing else.
        val d = policy.evaluate(still(0.30f), GuardProfile(0.25f))
        for ((k, v) in d.diagnostics) {
            assertTrue("diagnostic key $k looks like media", k.length < 32)
            assertTrue("diagnostic value for $k looks like a path", !v.contains('/'))
            assertTrue("diagnostic value for $k looks like media", v.length < 128)
        }
    }

    // ------------------------------------------------------------------ mandatory

    @Test
    fun aCheckThatCouldNotRunIsRefusedAtEverySensitivity() {
        // ⚠ THE MANDATORY CHECK. NaN is "the graph did not run". A gate that opens
        // when it breaks is worse than no gate, because everything upstream of it
        // believes the content was checked.
        for (i in 0..100) {
            val d = policy.evaluate(still(Float.NaN), GuardProfile(i / 100f))
            assertFalse("a failed check must be refused at sensitivity ${i / 100f}",
                d.permitted)
            assertEquals(ProtectionReason.CHECK_UNAVAILABLE, d.reason)
            assertEquals(DefaultProtectionPolicy.RULE_CHECK_UNAVAILABLE, d.ruleId)
            assertFalse("a failed check is not adjustable", d.adjustable)
        }
    }

    @Test
    fun theVideoFlaggedRateAllowanceIsMandatoryAtEverySensitivity() {
        // Upstream's `analyse_video` refuses a clip when more than 10% of its
        // samples trip the gate. The ALLOWANCE is not a dial setting.
        for (i in 0..100) {
            val profile = GuardProfile(i / 100f)
            // 11 samples, 2 flagged: 18.2%, over the 10% allowance.
            val over = ProtectionRequest(
                subject = 2, score = 0.01f, sampled = 11, flagged = 2)
            val d = policy.evaluate(over, profile)
            assertFalse("the video rate must be enforced at sensitivity ${i / 100f}",
                d.permitted)
            assertEquals(ProtectionReason.VIDEO_RATE_EXCEEDED, d.reason)
            assertEquals(DefaultProtectionPolicy.RULE_VIDEO_RATE, d.ruleId)
            assertFalse("the video rate is not adjustable", d.adjustable)
        }
    }

    @Test
    fun aVideoUnderTheAllowanceIsAllowedAtEverySensitivity() {
        // 10 samples, 1 flagged: exactly 10%, which is NOT over the allowance
        // (upstream refuses ABOVE 10%).
        for (i in 0..100) {
            val under = ProtectionRequest(
                subject = 2, score = 0.01f, sampled = 10, flagged = 1)
            assertTrue(
                "a clip at the allowance must pass at sensitivity ${i / 100f}",
                policy.evaluate(under, GuardProfile(i / 100f)).permitted)
        }
    }

    @Test
    fun theVideoAllowanceIsExactlyTenPercent() {
        // The number is upstream's, and the comparison is `>` not `>=`: a clip with
        // exactly 10% flagged is allowed.
        val exactly = ProtectionRequest(
            subject = 2, score = 0.01f, sampled = 10, flagged = 1)
        assertEquals(10.0, exactly.flaggedRate, 1e-9)
        assertTrue(policy.evaluate(exactly, GuardProfile(0f)).permitted)

        val over = ProtectionRequest(
            subject = 2, score = 0.01f, sampled = 10, flagged = 2)
        assertFalse(policy.evaluate(over, GuardProfile(0f)).permitted)
    }

    @Test
    fun consentIsRequiredAtEverySensitivity() {
        // The responsible-use notice. Mandatory, and not a dial setting.
        for (i in 0..100) {
            val d = policy.evaluate(still(0.01f, consent = false), GuardProfile(i / 100f))
            assertFalse("consent must be required at sensitivity ${i / 100f}",
                d.permitted)
            assertEquals(ProtectionReason.CONSENT_NOT_ACKNOWLEDGED, d.reason)
            assertEquals(DefaultProtectionPolicy.RULE_CONSENT, d.ruleId)
            assertFalse("consent is not adjustable", d.adjustable)
        }
    }

    @Test
    fun consentIsCheckedBeforeAnythingElse() {
        // A request with no consent AND a broken check must be refused for the
        // CONSENT reason, because consent is the thing the user can fix in a second
        // and sending them to the wrong screen is the worst outcome.
        val d = policy.evaluate(
            ProtectionRequest(subject = 1, score = Float.NaN, consentAcknowledged = false),
            GuardProfile(1f))
        assertEquals(ProtectionReason.CONSENT_NOT_ACKNOWLEDGED, d.reason)
    }

    @Test
    fun onlyTheScoreThresholdIsAdjustable() {
        // ⚠ The whole feature in one assertion: across every way a request can be
        // refused, exactly ONE of them is something the user can move.
        val adjustableReasons = mutableSetOf<ProtectionReason>()
        val allReasons = mutableSetOf<ProtectionReason>()

        for (i in 0..100) {
            val profile = GuardProfile(i / 100f)
            val cases = listOf(
                still(Float.NaN),
                still(0.99f),
                still(0.01f, consent = false),
                ProtectionRequest(subject = 2, score = 0.01f, sampled = 11, flagged = 2),
            )
            for (c in cases) {
                val d = policy.evaluate(c, profile)
                allReasons.add(d.reason)
                if (d.adjustable) adjustableReasons.add(d.reason)
            }
        }
        assertEquals(
            "exactly one rule may be adjustable",
            setOf(ProtectionReason.SCORE_ABOVE_THRESHOLD),
            adjustableReasons)
        assertTrue(
            "the mandatory rules must all be reachable",
            allReasons.containsAll(setOf(
                ProtectionReason.CHECK_UNAVAILABLE,
                ProtectionReason.VIDEO_RATE_EXCEEDED,
                ProtectionReason.CONSENT_NOT_ACKNOWLEDGED)))
    }

    @Test
    fun theAllowedDecisionIsAlsoMarkedAdjustable() {
        // An allow is the result of the adjustable rule not firing, so it moves with
        // the dial too. Marking it as not-adjustable would make the UI describe
        // every successful swap as something the user could not have achieved.
        val d = policy.evaluate(still(0.01f), GuardProfile(1f))
        assertTrue(d.permitted)
        assertTrue(d.adjustable)
    }

    // ------------------------------------------------------------------ determinism

    @Test
    fun theSameRequestAndProfileAlwaysGiveTheSameAnswer() {
        // Deterministic: a refusal has to be reproducible, or it cannot be
        // investigated.
        val request = still(0.30f)
        val profile = GuardProfile(0.4f)
        val first = policy.evaluate(request, profile)
        for (i in 0..50) {
            val again = policy.evaluate(request, profile)
            assertEquals(first.permitted, again.permitted)
            assertEquals(first.reason, again.reason)
            assertEquals(first.ruleId, again.ruleId)
            assertEquals(first.diagnostics, again.diagnostics)
        }
    }

    @Test
    fun theDecisionDescribesItself() {
        val d = policy.evaluate(still(0.30f), GuardProfile(0.4f))
        val s = d.describe()
        assertTrue("the summary must name the rule", s.contains(d.ruleId))
        assertTrue("the summary must say REFUSE", s.contains("REFUSE"))
    }

    // ------------------------------------------------------------------ threshold ties

    @Test
    fun aScoreExactlyOnTheThresholdIsAllowed() {
        // The comparison is `>`, not `>=`. A score exactly on the threshold is
        // allowed, and the boundary must behave the same way at every sensitivity.
        for (i in 0..100) {
            val profile = GuardProfile(i / 100f)
            val t = GuardProfile.thresholdFor(profile.sensitivity)
            assertTrue(
                "a score exactly on the threshold must pass at ${i / 100f}",
                policy.evaluate(still(t), profile).permitted)
            assertFalse(
                "a score just over the threshold must be refused at ${i / 100f}",
                policy.evaluate(still(t + 0.001f), profile).permitted)
        }
    }

    @Test
    fun differentProfilesProduceDifferentRuleIdentifiers() {
        // The rule identifier is the CONTRACT a calibration is written against, so
        // it has to be stable per rule -- and different rules must not share one.
        val over = policy.evaluate(still(0.9f), GuardProfile(1f)).ruleId
        val unavailable = policy.evaluate(still(Float.NaN), GuardProfile(1f)).ruleId
        val consent = policy.evaluate(still(0.01f, consent = false), GuardProfile(1f)).ruleId
        val rate = policy.evaluate(
            ProtectionRequest(subject = 2, score = 0.01f, sampled = 11, flagged = 2),
            GuardProfile(1f)).ruleId
        val all = listOf(over, unavailable, consent, rate)
        assertEquals("every rule must have its own identifier", all.size, all.toSet().size)
    }

    @Test
    fun thePolicyRevisionTravelsWithTheProfile() {
        // A revision bump is how a recalibration is told apart from a user having
        // moved the dial, so it has to reach the decision.
        val d = policy.evaluate(still(0.30f), GuardProfile(0.4f, policyRevision = 7))
        assertEquals("7", d.diagnostics["revision"])
        assertNotEquals(
            GuardProfile.CURRENT_POLICY_REVISION,
            GuardProfile(0.4f, policyRevision = 7).policyRevision)
    }
}
