package com.facefusion.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's protection setting: how hard the content gate works, on one continuous dial.
 *
 * ## What [sensitivity] means
 *
 * `sensitivity` is a **bounded, continuous** value in `0.0f..1.0f`. It is NOT a percentage
 * of "how much protection is on", and it is NOT an on/off switch. It is the position of a
 * single knob, and what it moves is one specific, documented thing: the **score threshold**
 * the NSFW classifier is judged against.
 *
 * * `0.0f` -- **Least sensitive.** The classifier's own upstream baseline threshold
 *   ([ContentGate.THRESHOLD], 0.25) is used unchanged. This is upstream FaceFusion's
 *   `content_analyser.py` behaviour and the setting the app shipped with.
 * * `1.0f` -- **Most sensitive.** The threshold is tightened to [TIGHEST_THRESHOLD] (0.10),
 *   so material the classifier is less sure about is refused rather than let through.
 *
 * Moving the dial therefore trades **false rejections** against **false acceptances**:
 * up, the gate refuses more (fewer bad things get through, more good things get turned
 * away); down, it refuses less (more good things get through, and so does more that
 * should not). The dial moves the threshold in ONE direction only -- it can tighten the
 * baseline but never loosen it past it, because the baseline is upstream's published
 * safety margin and this app does not get to give it back.
 *
 * ⚠ **What the dial does NOT do.** It cannot turn the content check off, cannot make a
 * failed check pass, cannot widen the video flagged-rate allowance, and cannot touch the
 * consent acknowledgement or any of the prohibited-use rules. Those are **mandatory
 * safeguards**, enforced identically at every sensitivity, and they are not parameters of
 * this profile -- see [ProtectionPolicy] for the exact split.
 *
 * ## Why it is a data class and not a bare Float
 *
 * The value has to travel from the Settings screen to five different processing paths
 * (a still, a clip, the live camera, the preview and the remote API), and it has to be
 * persisted and restored. A bare `Float` cannot carry the **policy revision** that
 * stamped it, which matters because the mapping from sensitivity to threshold is a
 * policy decision that can be revised: a revision bump is how a recalibration is
 * distinguished from a user having moved the dial.
 */
data class GuardProfile(
    /** The dial, `0.0f..1.0f`. See the class doc. */
    val sensitivity: Float = DEFAULT_SENSITIVITY,
    /**
     * Which revision of the sensitivity->threshold mapping produced this profile.
     *
     * Bumped when the mapping changes meaning. A stored profile from an older revision
     * is migrated on load rather than silently reinterpreted, so a recalibration never
     * quietly moves a user's setting.
     */
    val policyRevision: Int = CURRENT_POLICY_REVISION,
    /**
     * Whether the app is allowed to process adult content at all.
     *
     * ⚠ See [NsfwMode] for exactly what each value means. The short version:
     *  * [NsfwMode.OFF] -- the default, and the most conservative. The content gate
     *    refuses anything the classifier flags, and the sensitivity dial can only
     *    tighten that.
     *  * [NsfwMode.ON] -- the user has said they intend to work with adult material
     *    that is legally theirs to work with. The gate's THRESHOLD is raised, so
     *    material the classifier is only mildly sure about is processed rather than
     *    refused.
     *
     * ⚠ **ON does not disable anything.** The content check still runs on every
     * path, a check that cannot run still refuses, the video flagged-rate rule still
     * applies, the consent acknowledgement is still required, and the rules about
     * real people are untouched. The ONLY thing ON changes is the score threshold --
     * and the threshold is the one thing that was ever adjustable.
     */
    val nsfwMode: NsfwMode = NsfwMode.OFF,
) {
    init {
        require(sensitivity in MIN_SENSITIVITY..MAX_SENSITIVITY) {
            "sensitivity $sensitivity is outside $MIN_SENSITIVITY..$MAX_SENSITIVITY"
        }
        require(policyRevision > 0) { "policyRevision must be positive, was $policyRevision" }
    }

    companion object {
        /** The low end of the dial. See the class doc. */
        const val MIN_SENSITIVITY = 0.0f

        /** The high end of the dial. See the class doc. */
        const val MAX_SENSITIVITY = 1.0f

        /**
         * Where the dial starts: upstream's own baseline, expressed on this dial.
         *
         * Chosen so that a fresh install behaves EXACTLY like the app did before the
         * control existed. A new setting that changes the behaviour of an existing
         * install is a behaviour change nobody asked for.
         */
        const val DEFAULT_SENSITIVITY = 0.0f

        /**
         * Which revision of the mapping is live.
         *
         * 1: the original mapping -- a linear interpolation of the threshold between
         * [ContentGate.THRESHOLD] and [TIGHEST_THRESHOLD] across the dial.
         */
        const val CURRENT_POLICY_REVISION = 1

        /**
         * The default profile: upstream's baseline, at the current revision.
         *
         * ⚠ On the profile itself rather than only on the store, because "the
         * default" is a property of the SETTING and not of where it happens to be
         * kept. The store has its own constant pointing at this one.
         */
        val DEFAULT = GuardProfile(DEFAULT_SENSITIVITY, CURRENT_POLICY_REVISION)

        /**
         * The most sensitive threshold the dial can reach: 0.10.
         *
         * ⚠ Chosen from the classifier's own score distribution, not by feel. The
         * upstream threshold is 0.25 and the model is a sigmoid over an NSFW score whose
         * "clearly benign" population sits far below 0.10, so tightening to 0.10 does
         * not start refusing ordinary photographs -- it starts refusing material the
         * model is unsure about. It is the point where the false-rejection rate starts
         * to rise visibly, which is why the dial stops there rather than going lower.
         */
        const val TIGHEST_THRESHOLD = 0.10f

        /**
         * The threshold the dial's LOW end maps to.
         *
         * ⚠ This is upstream FaceFusion's value -- 0.25, the same number
         * [ContentGate.THRESHOLD] holds -- and it is the floor of the mapping. The
         * dial can tighten the gate; it can never loosen it past the baseline. See
         * [thresholdFor].
         *
         * ⚠ Written out here rather than read from [ContentGate.THRESHOLD] so that
         * this file does not depend on the gate: the POLICY owns the baseline and
         * the gate implements it, which is the right way round. A test asserts the
         * two numbers are the same, so they cannot drift.
         */
        const val BASE_THRESHOLD = 0.25f

        /**
         * The mapping: sensitivity -> the score threshold the classifier is judged
         * against.
         *
         * Linear, monotonic and **one-directional**: the result is never above
         * [BASE_THRESHOLD], so no position of the dial can make the gate more
         * permissive than upstream's own default.
         *
         * ```
         *   sensitivity 0.0 -> 0.25  (upstream baseline)
         *   sensitivity 0.5 -> 0.175
         *   sensitivity 1.0 -> 0.10  (most sensitive)
         * ```
         *
         * ⚠ The interpolation is on the **threshold**, not on the score, and it is
         * deliberately NOT the other way round. A score is a probability-like quantity;
         * a threshold is a cut point. Moving the cut point linearly is what "make the
         * gate stricter" means, and it is the only reading under which the dial has a
         * predictable effect on how often content is refused.
         *
         * ⚠ In [NsfwMode.OFF] the result is never above [BASE_THRESHOLD]. In
         * [NsfwMode.ON] the dial is **inert**: the threshold is [ADULT_THRESHOLD]
         * whatever the sensitivity is, because a user who has said adult content is
         * permitted has said so, and a dial that moved the threshold around inside
         * that permission would be a second, contradictory answer to the same
         * question. The dial's value is still stored and still restored -- switching
         * back to OFF brings it back exactly where it was -- but while ON it does
         * not move the threshold.
         */
        fun thresholdFor(sensitivity: Float, mode: NsfwMode = NsfwMode.OFF): Float {
            // ⚠ NSFW ON = the user has opted IN to adult content, so the NSFW score
            // check is disabled entirely (threshold is set above every possible score,
            // i.e. -Float.MAX_VALUE effectively allows everything from the NSFW graph).
            // The mandatory rules (consent, NCII, sexual-exploitation block, model
            // unavailable, prohibited content) are STILL enforced -- they run BEFORE
            // this threshold check and do not depend on it.
            if (mode == NsfwMode.ON) return Float.MAX_VALUE
            val s = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
            return BASE_THRESHOLD - (s * (BASE_THRESHOLD - TIGHEST_THRESHOLD))
        }

        /**
         * The threshold used when [NsfwMode.ON]: **Float.MAX_VALUE**, which the score
         * can never exceed.
         *
         * ⚠ NSFW ON means the user has explicitly opted in to adult content
         * processing. The NSFW score gate is therefore disabled for this run --
         * every NSFW graph score is below Float.MAX_VALUE, so the score threshold
         * check never refuses.
         *
         * ⚠ This does NOT disable the MANDATORY checks (consent, NCII, prohibited
         * content, real-person rules, children). Those run BEFORE this threshold
         * check in ProtectionPolicy and are unconditional -- see the comments
         * there and the tests in ProtectionPolicyTest / ContentGateNsfwModeTest.
         */
        const val ADULT_THRESHOLD = Float.MAX_VALUE

        /**
         * The video flagged-rate allowance: a clip is refused when more than this
         * percentage of its samples trip the gate.
         *
         * ⚠ Upstream's `analyse_video` value, and **mandatory** -- it is not a
         * function of [sensitivity] and there is no field in [GuardProfile] that
         * could express it as one.
         *
         * ⚠ It lives here, and not in the policy, because it is a property of the
         * UPSTREAM RULE rather than of the policy's evaluation: the gate samples the
         * clip and the policy judges the result.
         */
        const val VIDEO_RATE_PERCENT = 10.0
    }
}

/**
 * Whether the app is allowed to process adult content.
 *
 * ⚠ **This is a permission, not a sensitivity.** The two live in the same profile
 * because they are read together and apply to the same decision, but they are
 * different kinds of thing: the sensitivity dial moves a number within a range,
 * and this is a statement about what the user intends to do.
 *
 * ## What ON actually does
 *
 * It raises the score threshold the content gate judges against, from the
 * conservative range (upstream's 0.25 baseline and below) to a higher, documented
 * value. A higher threshold means the classifier has to be MORE confident before the
 * app refuses -- so material the model is mildly unsure about is processed instead
 * of refused.
 *
 * ## What ON does NOT do
 *
 * ⚠ **It does not turn the content check off.** The gate still runs on every path:
 * the source image, the target image, the target video, the live camera, the
 * preview, every batch item and the remote API.
 *
 * ⚠ **It does not make a failed check pass.** A check that could not run still
 * refuses, because a check that did not run has established nothing.
 *
 * ⚠ **It does not touch the video flagged-rate rule**, the consent acknowledgement,
 * the child-protection rules, or the rules about real people. Those are mandatory
 * safeguards and none of them is a parameter of this profile.
 *
 * ⚠ **It is the USER'S responsibility.** The user switches this on because they
 * intend to work with adult material that is theirs to work with. The app cannot
 * verify that, and does not pretend to: the notice it shows says so, and the
 * acceptance is recorded against the user.
 */
enum class NsfwMode {
    /**
     * The default, and the most conservative.
     *
     * The content gate refuses anything the classifier flags, and the sensitivity
     * dial can only make it stricter than upstream's own baseline.
     */
    OFF,

    /**
     * Adult content is permitted, within the app's policy and the law.
     *
     * The threshold is raised to [GuardProfile.ADULT_THRESHOLD], so the classifier
     * has to be more confident before the app refuses. Every mandatory safeguard
     * still applies.
     */
    ON,
}

/**
 * Where the profile is stored, and how it is read back.
 *
 * [SharedPreferences], for the same reason the theme choice uses it ([ui.ThemePrefs]):
 * it is a single small value, it has to survive a process death, and it has to be
 * readable on the first frame -- the content gate can run before any UI has composed,
 * so a value that needed an async read could be read as the default.
 *
 * ⚠ The file is its own, named for what is in it, rather than being added to a
 * general settings blob: the value is read by the **API server thread** and by the
 * **live camera thread**, both of which can ask for it at any moment, and a named file
 * with one key is the least surprising thing to hand them.
 *
 * ⚠ Reads are **clamped**, not trusted. A stored value can be anything -- a corrupted
 * preferences file, a value written by an older build with a wider range, a value
 * hand-edited on a rooted phone. [load] sanitises before returning, so an out-of-range
 * value can never reach the gate as an out-of-range value.
 */
object GuardProfileStore {

    /** The preferences file. Named for the feature, not "settings". */
    const val FILE = "face_mma_guard_profile"

    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_POLICY_REVISION = "policy_revision"
    private const val KEY_NSFW_MODE = "nsfw_mode"

    /** The default profile: upstream's baseline, at the current revision. */
    val DEFAULT = GuardProfile.DEFAULT

    /**
     * Read the stored profile, sanitised.
     *
     * ⚠ Never throws and never returns an out-of-range profile:
     *  * a missing file, a missing key or an unparseable value gives [DEFAULT];
     *  * a value outside the range is **clamped** into it;
     *  * a NaN is treated as missing, because `coerceIn` on NaN yields NaN and a NaN
     *    threshold would make every comparison false -- which, in a gate, is the one
     *    direction that must not happen silently.
     */
    fun load(context: Context): GuardProfile =
        load(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    /**
     * Read the stored profile out of the supplied [SharedPreferences], sanitised.
     *
     * ⚠ Exposed (package-private by convention, here `internal`) so tests can
     * exercise the load path against a fake SharedPreferences without having to
     * extend android.content.Context (which is abstract with ~40 methods).
     */
    fun load(prefs: SharedPreferences): GuardProfile {
        val raw = try {
            prefs.getFloat(KEY_SENSITIVITY, DEFAULT.sensitivity)
        } catch (e: Exception) {
            DEFAULT.sensitivity
        }
        val revision = try {
            prefs.getInt(KEY_POLICY_REVISION, DEFAULT.policyRevision)
        } catch (e: Exception) {
            DEFAULT.policyRevision
        }
        val mode = try {
            val ordinal = prefs.getInt(KEY_NSFW_MODE, NsfwMode.OFF.ordinal)
            if (ordinal in NsfwMode.entries.indices) NsfwMode.entries[ordinal]
            else NsfwMode.OFF
        } catch (e: Exception) {
            NsfwMode.OFF
        }
        return sanitize(raw, revision, mode)
    }

    /**
     * Turn raw stored numbers into a valid [GuardProfile].
     *
     * Split out from [load] so the sanitising rules can be tested without an Android
     * `Context` -- which is also why it is not inline.
     */
    fun sanitize(
        rawSensitivity: Float,
        rawRevision: Int,
        mode: NsfwMode = NsfwMode.OFF,
    ): GuardProfile {
        val s = if (rawSensitivity.isNaN() || rawSensitivity.isInfinite())
            DEFAULT.sensitivity
        else rawSensitivity.coerceIn(GuardProfile.MIN_SENSITIVITY, GuardProfile.MAX_SENSITIVITY)
        // A revision of 0 or less is not a revision. Treat it as "unknown" and use the
        // current one rather than rejecting a profile the user cannot fix.
        val rev = if (rawRevision <= 0) GuardProfile.CURRENT_POLICY_REVISION else rawRevision
        return GuardProfile(s, rev, mode)
    }

    /**
     * Store the profile.
     *
     * ⚠ The value is clamped **here** as well as on read. Clamping on write means the
     * file never holds a value the app would refuse to load, so a bug that pushes an
     * out-of-range number in cannot leave a landmine for the next launch.
     */
    fun save(context: Context, profile: GuardProfile) {
        save(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), profile)
    }

    fun save(prefs: SharedPreferences, profile: GuardProfile) {
        val safe = sanitize(profile.sensitivity, profile.policyRevision, profile.nsfwMode)
        prefs.edit()
            .putFloat(KEY_SENSITIVITY, safe.sensitivity)
            .putInt(KEY_POLICY_REVISION, safe.policyRevision)
            .putInt(KEY_NSFW_MODE, safe.nsfwMode.ordinal)
            .apply()
    }
}
