package com.facefusion.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A record of a protection decision, kept so that a legitimate refusal can be
 * investigated.
 *
 * ## What it may contain
 *
 * Rule identifiers, reason codes, thresholds, scores, counts and timestamps. **No**
 * image data, no file names, no biometric features, no user text, no paths.
 *
 * ⚠ This is not a stylistic preference. The app's own privacy statement says photos
 * and videos are never uploaded and that a bug report contains no media; a
 * diagnostics log that carried a filename would break the first half of that the
 * moment someone attached it to a bug report. So the rule is enforced by the TYPE:
 * a [ProtectionEvent] can only be built from numbers and short identifiers, and
 * there is nowhere in it to put a Bitmap.
 *
 * The subject is stored as a **resource id** rather than a sentence, so the record
 * says "the target video" without carrying the text of a sentence that would have to
 * be translated, and without carrying anything the user typed.
 */
data class ProtectionEvent(
    /** Milliseconds since the epoch. */
    val timestampMs: Long,
    /** The rule identifier, e.g. `nsfw.score_threshold`. */
    val ruleId: String,
    /** The reason category, e.g. `SCORE_ABOVE_THRESHOLD`. */
    val reason: String,
    /** Whether the decision was to refuse. */
    val refused: Boolean,
    /** Whether the user could have changed this outcome with the dial. */
    val adjustable: Boolean,
    /** The subject, as a string resource id. */
    val subjectRes: Int,
    /** The numbers: score, threshold, sensitivity, counts. Never media. */
    val diagnostics: Map<String, String>,
) {
    /** The JSON form, as stored. Key order is fixed so two records compare cleanly. */
    fun toJson(): JSONObject {
        val d = JSONObject()
        d.put("t", timestampMs)
        d.put("rule", ruleId)
        d.put("reason", reason)
        d.put("refused", refused)
        d.put("adjustable", adjustable)
        d.put("subject", subjectRes)
        val dd = JSONObject()
        for (k in diagnostics.keys.sorted()) dd.put(k, diagnostics[k])
        d.put("d", dd)
        return d
    }
}

/**
 * A bounded, on-device log of protection decisions.
 *
 * ## Why it is bounded
 *
 * The log lives in the app's private files and is never uploaded by the app. It is
 * bounded anyway, because an unbounded log in a private directory is a log that grows
 * until the phone runs out of room, and this app's users are the people least likely
 * to be reading a stack trace.
 *
 * ## Why it is on-device at all
 *
 * A false rejection is a real failure mode of a classifier: a legitimate photo gets a
 * score just over the threshold and is refused with no explanation and no way to
 * argue with it. Without a record of what the score was and which rule fired, the
 * only available response is to move a slider blindly. With it, the calibration can
 * be done against evidence: "ordinary portraits are being scored at 0.26-0.30, and
 * the threshold is 0.25" is a finding; "the app blocked me" is not.
 *
 * ⚠ The log is evidence for a CALIBRATION of the threshold, not a way for a user to
 * talk the app out of a refusal. Nothing in it can change a decision: it is written
 * after the decision is made.
 */
object ProtectionDiagnostics {

    /** The file the log lives in, in the app's private files dir. */
    const val FILE = "protection_diagnostics.json"

    /** How many events are kept. Older ones are dropped. */
    const val MAX_EVENTS = 200

    /** The tag the log lines go out under, for `adb logcat`. */
    const val TAG = "ffguard"

    /**
     * Record a decision.
     *
     * ⚠ Only REFUSALS are recorded by default. An allow is the overwhelmingly common
     * case and would crowd the log out of the refusals it exists to explain. The
     * allow case is still available for a calibration sweep, via [recordAllow].
     */
    fun record(context: Context, decision: ProtectionDecision, subjectRes: Int) {
        if (decision.permitted) return
        write(context, ProtectionEvent(
            timestampMs = System.currentTimeMillis(),
            ruleId = decision.ruleId,
            reason = decision.reason.name,
            refused = true,
            adjustable = decision.adjustable,
            subjectRes = subjectRes,
            diagnostics = decision.diagnostics,
        ))
    }

    /** Record an ALLOW as well. For a calibration sweep, not for normal use. */
    fun recordAllow(context: Context, decision: ProtectionDecision, subjectRes: Int) {
        if (!decision.permitted) return
        write(context, ProtectionEvent(
            timestampMs = System.currentTimeMillis(),
            ruleId = decision.ruleId,
            reason = decision.reason.name,
            refused = false,
            adjustable = decision.adjustable,
            subjectRes = subjectRes,
            diagnostics = decision.diagnostics,
        ))
    }

    /** Append one event, trimming the oldest. */
    private fun write(context: Context, event: ProtectionEvent) {
        val events = read(context).toMutableList()
        events.add(event)
        while (events.size > MAX_EVENTS) events.removeAt(0)
        save(context, events)
    }

    /** The recorded events, oldest first. Empty when there is no log. */
    fun read(context: Context): List<ProtectionEvent> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val dd = o.optJSONObject("d")
                val map = mutableMapOf<String, String>()
                if (dd != null) {
                    for (k in dd.keys()) map[k] = dd.optString(k)
                }
                ProtectionEvent(
                    timestampMs = o.optLong("t"),
                    ruleId = o.optString("rule"),
                    reason = o.optString("reason"),
                    refused = o.optBoolean("refused"),
                    adjustable = o.optBoolean("adjustable"),
                    subjectRes = o.optInt("subject"),
                    diagnostics = map,
                )
            }
        } catch (e: Exception) {
            // A corrupt log is not worth crashing over, and it is not worth keeping:
            // an unreadable diagnostics file is exactly the case where the next
            // refusal is the one that needs recording.
            emptyList()
        }
    }

    /** Every recorded refusal, newest first. What the diagnostics screen shows. */
    fun refusals(context: Context): List<ProtectionEvent> =
        read(context).filter { it.refused }.reversed()

    /**
     * A summary of the refusals, for a calibration sweep.
     *
     * Counts per rule and per reason, and the score range of the refusals that came
     * from the adjustable rule. **No media**, and no filenames.
     */
    fun summary(context: Context): String {
        val events = refusals(context)
        if (events.isEmpty()) return "no refusals recorded"
        val byRule = events.groupingBy { it.ruleId }.eachCount()
        val byReason = events.groupingBy { it.reason }.eachCount()
        val scores = events.mapNotNull { it.diagnostics["score"]?.toFloatOrNull() }.sorted()
        val sb = StringBuilder()
        sb.append("${events.size} refusal(s)\n")
        for ((k, v) in byRule.entries.sortedBy { it.key }) sb.append("  rule $k: $v\n")
        for ((k, v) in byReason.entries.sortedBy { it.key }) sb.append("  reason $k: $v\n")
        if (scores.isNotEmpty()) {
            sb.append("  score range: %.4f .. %.4f\n".format(scores.first(), scores.last()))
        }
        return sb.toString()
    }

    /** The events as a JSON array, for sharing with a bug report. */
    fun toJson(context: Context): String {
        val arr = JSONArray()
        for (e in read(context)) arr.put(e.toJson())
        return arr.toString()
    }

    /** Delete the log. Offered in the diagnostics screen, so it is a real control. */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
        Unit
    }

    /** The file the log lives in. */
    fun file(context: Context) = java.io.File(context.getFilesDir(), FILE)

    /** Write the events out. */
    private fun save(context: Context, events: List<ProtectionEvent>) {
        val arr = JSONArray()
        for (e in events) arr.put(e.toJson())
        try {
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            // A diagnostics log that cannot be written is not a reason to refuse a
            // swap. The gate's decision stands on its own.
        }
    }
}
