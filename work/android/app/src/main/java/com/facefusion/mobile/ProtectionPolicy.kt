package com.facefusion.mobile

/**
 * The protection policy contract: one place that decides whether a request may proceed.
 *
 * ## Why this exists
 *
 * The content gate ([ContentGate]) answers a question about ONE image or clip at a
 * time: "is this score above the threshold?". That is the right question for a
 * classifier, and it is the only question the classifier can answer.
 *
 * It is not, on its own, a policy. A policy has to answer "may this REQUEST proceed,
 * and if not, why not, and what was the rule?". Before this layer existed the answer
 * to "why was this refused" was scattered across five call sites, each with its own
 * wording, and the adjustable part of the protection (the sensitivity dial) and the
 * mandatory part of it (the safeguards) had no single place that said which was which.
 *
 * ## The split this interface makes explicit
 *
 * There are exactly two kinds of rule, and the difference is not a matter of degree:
 *
 *  * **ADJUSTABLE** -- the classifier's score threshold, and nothing else. It is a
 *    function of [GuardProfile.sensitivity], it is the ONLY thing the dial moves, and
 *    it is the only thing a user can change about how the app decides.
 *  * **MANDATORY** -- everything else. The gate must run at all; a gate that could not
 *    run is a refusal, not a pass; the video flagged-rate allowance is fixed; the
 *    consent acknowledgement is required; prohibited uses are prohibited. These are
 *    enforced identically at every sensitivity, and [GuardProfile] has no field that
 *    could express them, because the point is that they are not adjustable.
 *
 * A policy that could not be told apart from its rules would eventually be given a
 * knob that turns one of the mandatory rules off. Splitting them here means that is a
 * type error rather than a bug.
 *
 * ## Determinism
 *
 * [evaluate] is a pure function of its arguments: the same request and the same
 * profile always produce the same decision, with the same reason code and the same
 * diagnostics. Nothing in it reads the clock, the network, or any mutable global.
 * That is what makes a refusal reproducible and therefore investigable -- which is the
 * whole point of [ProtectionDiagnostics].
 */
interface ProtectionPolicy {

    /**
     * Decide whether [request] may proceed under [profile].
     *
     * @return a [ProtectionDecision] that ALWAYS says what happened and why. Never
     *   null, never ambiguous: a caller that has to guess is a caller that can guess
     *   wrong in the permissive direction.
     */
    fun evaluate(request: ProtectionRequest, profile: GuardProfile): ProtectionDecision
}

/**
 * One thing the app wants to do, as the policy sees it.
 *
 * ⚠ Deliberately carries **no media**. A request that handed the policy a Bitmap
 * could have that Bitmap logged, cached, or copied into a diagnostic; keeping the
 * media out of the request is what makes "the diagnostics never contain user content"
 * a property of the types rather than a promise about the code.
 */
data class ProtectionRequest(
    /**
     * What is being checked. One of the `R.string.gate_subject_*` values as a
     * RESOURCE, not a sentence -- a resource cannot be mistranslated into something
     * that no longer names a subject.
     */
    val subject: Int,

    /**
     * The classifier's score for this request, if it has one.
     *
     * `NaN` means "the check did not run", which is **not** the same as "the score was
     * low" and is never allowed to be treated as a pass. See [ProtectionDecision].
     */
    val score: Float = Float.NaN,

    /**
     * For a clip: how many samples were taken and how many tripped the gate.
     *
     * A still leaves these at their defaults. They are here because the video rule
     * (a flagged RATE, not a flagged frame) is mandatory and needs to be reported with
     * the same shape as every other decision.
     */
    val sampled: Int = 1,
    val flagged: Int = 0,

    /**
     * A short technical note about a FAULT -- a native error string, a missing model.
     *
     * ⚠ Not translated and not user-facing: it goes into diagnostics and the bug
     * report, where a translated QNN message matches nothing anyone can search.
     */
    val detail: String = "",

    /**
     * Whether the user has accepted the responsible-use notice.
     *
     * **Mandatory**, and it is part of the REQUEST rather than the profile on
     * purpose: consent is a fact about the user and the session, not a dial setting,
     * and putting it in [GuardProfile] would have made it look like something the
     * slider could influence.
     *
     * Defaults to `true` so that a caller that has not thought about consent gets the
     * behaviour it had before this layer existed, rather than having every request
     * suddenly refused for a reason it never asked about. Every real call site sets
     * it explicitly.
     */
    val consentAcknowledged: Boolean = true,
) {
    /** The flagged rate of a clip, in percent. */
    val flaggedRate: Double
        get() = if (sampled <= 0) 0.0 else 100.0 * flagged / sampled
}

/**
 * Why the policy decided what it decided.
 *
 * These are **rule identifiers**, not sentences: they are stable across languages and
 * across releases, they go into the diagnostics log, and they are what a calibration
 * change is made against. A user-facing message is chosen from the reason code at the
 * call site, from the app's own string resources.
 */
enum class ProtectionReason {
    /** The request passed every rule. */
    ALLOWED,

    /**
     * The classifier's score was above the threshold this profile allows.
     *
     * **Adjustable.** This is the rule the sensitivity dial moves.
     */
    SCORE_ABOVE_THRESHOLD,

    /**
     * The check could not run -- the model is missing, the graph failed, the file
     * could not be read.
     *
     * **Mandatory.** A check that could not run has established nothing, so the
     * request is refused. There is no sensitivity at which this becomes a pass.
     */
    CHECK_UNAVAILABLE,

    /**
     * Too much of a clip tripped the gate.
     *
     * **Mandatory.** The allowance is upstream's `analyse_video` rate and is not a
     * parameter of [GuardProfile].
     */
    VIDEO_RATE_EXCEEDED,

    /**
     * The app has not recorded the user's acceptance of the responsible-use notice.
     *
     * **Mandatory.** It is a precondition of using the app at all.
     */
    CONSENT_NOT_ACKNOWLEDGED,
}

/**
 * The outcome of one policy evaluation.
 *
 * ⚠ [permitted] is `true` for exactly one case. There is no "not blocked", and no
 * "allowed with warnings": a request is either permitted or it is not, and every
 * other outcome is a refusal with a reason.
 */
data class ProtectionDecision(
    /** Whether the request may proceed. */
    val permitted: Boolean,

    /** The rule that decided it. */
    val reason: ProtectionReason,

    /**
     * The rule's stable identifier, e.g. `nsfw.score_threshold`.
     *
     * ⚠ Separate from [reason] because the reason is a category and the identifier is
     * the CONTRACT: a calibration is written against the identifier, so it keeps
     * working when the human-readable meaning of a category is reworded.
     */
    val ruleId: String,

    /**
     * Everything needed to explain the decision, WITHOUT the media.
     *
     * ⚠ Numbers only, plus a technical note. No image, no filename, no biometric, no
     * user text. This is what is safe to write to a log.
     */
    val diagnostics: Map<String, String> = emptyMap(),

    /**
     * Whether the decision was one the user could have changed with the dial.
     *
     * `false` for every mandatory rule. The UI uses it to say, honestly, whether a
     * refusal was something the slider could have avoided.
     */
    val adjustable: Boolean = false,
) {
    /** A one-line summary for the log and the diagnostics screen. */
    fun describe(): String =
        "$ruleId -> ${if (permitted) "ALLOW" else "REFUSE"} ($reason)" +
            (if (diagnostics.isEmpty()) "" else " " + diagnostics.entries.joinToString(" ") { "${it.key}=${it.value}" })
}

/**
 * The policy this app runs.
 *
 * A single implementation, used by every path that processes media -- the still path,
 * the clip path, the live camera, the preview, the batch runner and the remote API.
 * There is deliberately no second engine: a second engine is a second set of rules, and
 * two rule sets drift.
 */
object DefaultProtectionPolicy : ProtectionPolicy {

    /**
     * The identifier of the adjustable rule. The calibration log and the tests both
     * name it, so it is a constant rather than a literal at each use.
     */
    const val RULE_SCORE_THRESHOLD = "nsfw.score_threshold"

    /** The identifier of the mandatory "the check must run" rule. */
    const val RULE_CHECK_UNAVAILABLE = "nsfw.check_unavailable"

    /** The identifier of the mandatory video-rate rule. */
    const val RULE_VIDEO_RATE = "nsfw.video_rate"

    /** The identifier of the mandatory consent rule. */
    const val RULE_CONSENT = "consent.not_acknowledged"

    override fun evaluate(
        request: ProtectionRequest,
        profile: GuardProfile,
    ): ProtectionDecision {
        // ---------------------------------------------------------------- mandatory
        //
        // Consent first: it is the only rule that does not even look at the media, and
        // it is the one a user can satisfy in a second without touching a slider. A
        // refusal that says "the check could not run" when the real reason is "the
        // notice has not been accepted" sends the user to the wrong screen.
        if (!request.consentAcknowledged) {
            return ProtectionDecision(
                permitted = false,
                reason = ProtectionReason.CONSENT_NOT_ACKNOWLEDGED,
                ruleId = RULE_CONSENT,
                diagnostics = mapOf("consent" to "missing"),
                adjustable = false,
            )
        }

        // The check has to have run. NaN is the "did not run" answer, and the gate's
        // own contract is that an unavailable check is a refusal.
        if (request.score.isNaN()) {
            return ProtectionDecision(
                permitted = false,
                reason = ProtectionReason.CHECK_UNAVAILABLE,
                ruleId = RULE_CHECK_UNAVAILABLE,
                diagnostics = buildMap {
                    put("sensitivity", fmt(profile.sensitivity))
                    put("threshold", fmt(GuardProfile.thresholdFor(profile.sensitivity, profile.nsfwMode)))
                    if (request.detail.isNotBlank()) put("detail", request.detail)
                },
                adjustable = false,
            )
        }

        // The video rule: upstream's `analyse_video` refuses a clip when more than
        // VIDEO_RATE_PERCENT of its samples trip the gate. Mandatory, and checked
        // against the SAMPLES -- not against the worst score, which is the still rule.
        if (request.sampled > 1 && request.flaggedRate > GuardProfile.VIDEO_RATE_PERCENT) {
            return ProtectionDecision(
                permitted = false,
                reason = ProtectionReason.VIDEO_RATE_EXCEEDED,
                ruleId = RULE_VIDEO_RATE,
                diagnostics = mapOf(
                    "sampled" to request.sampled.toString(),
                    "flagged" to request.flagged.toString(),
                    "rate" to "%.1f".format(request.flaggedRate),
                    "allowance" to "%.1f".format(GuardProfile.VIDEO_RATE_PERCENT),
                ),
                adjustable = false,
            )
        }

        // ---------------------------------------------------------------- adjustable
        //
        // The score threshold, and the ONLY thing the sensitivity dial moves. This is
        // the one branch whose answer changes when the user drags the slider.
        val threshold = GuardProfile.thresholdFor(profile.sensitivity, profile.nsfwMode)
        if (request.score > threshold) {
            return ProtectionDecision(
                permitted = false,
                reason = ProtectionReason.SCORE_ABOVE_THRESHOLD,
                ruleId = RULE_SCORE_THRESHOLD,
                diagnostics = mapOf(
                    "score" to fmt(request.score),
                    "threshold" to fmt(threshold),
                    "sensitivity" to fmt(profile.sensitivity),
                    "revision" to profile.policyRevision.toString(),
                    // The mode, so a refusal can be read against the setting that
                    // produced it. A calibration that mixes OFF and ON refusals
                    // without knowing which was in force is not a calibration.
                    "mode" to profile.nsfwMode.name,
                ),
                adjustable = true,
            )
        }

        // ---------------------------------------------------------------- allowed
        return ProtectionDecision(
            permitted = true,
            reason = ProtectionReason.ALLOWED,
            ruleId = RULE_ALLOWED,
            diagnostics = mapOf(
                "score" to fmt(request.score),
                "threshold" to fmt(threshold),
                "sensitivity" to fmt(profile.sensitivity),
                "mode" to profile.nsfwMode.name,
            ),
            adjustable = true,
        )
    }

    /** The identifier of the "nothing objected" outcome. */
    const val RULE_ALLOWED = "nsfw.allowed"

    /** A number as a string with a fixed shape, so diagnostics line up in a log. */
    private fun fmt(v: Float): String = "%.4f".format(v)
}
