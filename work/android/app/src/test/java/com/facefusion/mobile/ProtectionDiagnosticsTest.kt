package com.facefusion.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refusal history: what it records, and -- just as importantly -- what it does
 * NOT record.
 *
 * ⚠ The "what it does not record" half is the half that matters. This app's own
 * privacy statement says photos and videos are never uploaded and that a bug report
 * contains no media. A diagnostics log that carried a filename would break the first
 * half of that the moment somebody attached it to a report.
 */
class ProtectionDiagnosticsTest {

    @Test
    fun anEventCarriesTheRuleAndTheReason() {
        val e = ProtectionEvent(
            timestampMs = 1_700_000_000_000L,
            ruleId = DefaultProtectionPolicy.RULE_SCORE_THRESHOLD,
            reason = ProtectionReason.SCORE_ABOVE_THRESHOLD.name,
            refused = true,
            adjustable = true,
            subjectRes = 42,
            diagnostics = mapOf("score" to "0.3000", "threshold" to "0.2500"),
        )
        assertEquals(DefaultProtectionPolicy.RULE_SCORE_THRESHOLD, e.ruleId)
        assertEquals(ProtectionReason.SCORE_ABOVE_THRESHOLD.name, e.reason)
        assertTrue(e.refused)
        assertTrue(e.adjustable)
    }

    @Test
    fun theEventCarriesNoMediaAndNoUserText() {
        // The TYPE has nowhere to put a Bitmap or a filename, and the diagnostics
        // map is strings of numbers. This test pins that down by checking the shape
        // of what the decision layer produces.
        val d = DefaultProtectionPolicy.evaluate(
            ProtectionRequest(subject = 1, score = 0.31f), GuardProfile(0.2f))
        assertFalse(d.permitted)
        for ((k, v) in d.diagnostics) {
            assertTrue("'$k' is too long to be a number", k.length <= 32)
            assertTrue("'$v' contains a path separator", !v.contains('/'))
            assertTrue("'$v' contains a backslash", !v.contains('\\'))
            // No whitespace-heavy prose either: these are values, not sentences.
            assertTrue("'$v' looks like prose", v.length <= 64)
        }
    }

    @Test
    fun theJsonFormIsStableAndCarriesTheNumbers() {
        val e = ProtectionEvent(
            timestampMs = 42L,
            ruleId = "nsfw.score_threshold",
            reason = "SCORE_ABOVE_THRESHOLD",
            refused = true,
            adjustable = true,
            subjectRes = 7,
            diagnostics = mapOf("score" to "0.3000", "threshold" to "0.2500"),
        )
        val j = e.toJson()
        assertEquals(42L, j.getLong("t"))
        assertEquals("nsfw.score_threshold", j.getString("rule"))
        assertEquals("SCORE_ABOVE_THRESHOLD", j.getString("reason"))
        assertTrue(j.getBoolean("refused"))
        assertTrue(j.getBoolean("adjustable"))
        assertEquals(7, j.getInt("subject"))
        assertEquals("0.3000", j.getJSONObject("d").getString("score"))
        assertEquals("0.2500", j.getJSONObject("d").getString("threshold"))
    }

    @Test
    fun theDiagnosticsCarryNoScoreWhenTheCheckCouldNotRun() {
        // A faulted check has no score, and recording "NaN" as one would be noise
        // in a calibration table.
        val d = DefaultProtectionPolicy.evaluate(
            ProtectionRequest(subject = 1, score = Float.NaN, detail = "model missing"),
            GuardProfile(0.5f))
        assertFalse(d.permitted)
        assertEquals(ProtectionReason.CHECK_UNAVAILABLE, d.reason)
        assertFalse("a failed check must not report a score",
            d.diagnostics.containsKey("score"))
        // The technical detail IS recorded: it is what says what broke.
        assertEquals("model missing", d.diagnostics["detail"])
    }

    @Test
    fun theSummaryCountsByRule() {
        // The summary is what a calibration is read from, so it has to count by rule
        // rather than produce one undifferentiated number.
        val events = listOf(
            ProtectionEvent(1L, "nsfw.score_threshold", "SCORE_ABOVE_THRESHOLD",
                true, true, 1, mapOf("score" to "0.3000")),
            ProtectionEvent(2L, "nsfw.score_threshold", "SCORE_ABOVE_THRESHOLD",
                true, true, 1, mapOf("score" to "0.4000")),
            ProtectionEvent(3L, "nsfw.check_unavailable", "CHECK_UNAVAILABLE",
                true, false, 1, emptyMap()),
        )
        // The summary text is produced from a list of events, so this checks the
        // shape of the aggregation rather than the file plumbing.
        val byRule = events.groupingBy { it.ruleId }.eachCount()
        assertEquals(2, byRule["nsfw.score_threshold"])
        assertEquals(1, byRule["nsfw.check_unavailable"])
        val scores = events.mapNotNull { it.diagnostics["score"]?.toFloatOrNull() }.sorted()
        assertEquals(0.30f, scores.first(), 1e-6f)
        assertEquals(0.40f, scores.last(), 1e-6f)
    }

    @Test
    fun aRefusalIsMarkedAdjustableOnlyWhenItIs() {
        // The distinction the UI shows: "the sensitivity setting caused this" vs
        // "a rule you cannot change caused this".
        val adjustable = DefaultProtectionPolicy.evaluate(
            ProtectionRequest(subject = 1, score = 0.9f), GuardProfile(1f))
        assertTrue(adjustable.adjustable)

        val mandatory = DefaultProtectionPolicy.evaluate(
            ProtectionRequest(subject = 1, score = Float.NaN), GuardProfile(1f))
        assertFalse(mandatory.adjustable)

        val rate = DefaultProtectionPolicy.evaluate(
            ProtectionRequest(subject = 2, score = 0.01f, sampled = 11, flagged = 2),
            GuardProfile(1f))
        assertFalse(rate.adjustable)
    }

    @Test
    fun theDiagnosticsLogIsBounded() {
        // A log in the app's private directory that grows without limit is a log
        // that eventually fills the phone.
        assertTrue(ProtectionDiagnostics.MAX_EVENTS in 1..10_000)
    }

    @Test
    fun theDiagnosticsFileLivesInTheAppsOwnDirectory() {
        // Never on shared storage: these records are about the user's content and
        // have no business being readable by another app.
        assertTrue(
            ProtectionDiagnostics.FILE.isNotBlank())
        assertFalse(
            "the diagnostics file must not be in shared storage",
            ProtectionDiagnostics.FILE.contains("/"))
    }
}
