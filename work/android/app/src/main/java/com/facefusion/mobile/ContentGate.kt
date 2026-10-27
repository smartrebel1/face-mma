package com.facefusion.mobile

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * The NSFW content gate, with upstream's policy.
 *
 * It **blocks**: FaceFusion's `content_analyser.py` refuses to process flagged content and
 * so does this port (decided 2026-08-24). Everything here is upstream's rule, ported:
 *
 *  * a still is checked once ([inspectStill], `analyse_image`);
 *  * a video is sampled **one frame per second** and refused when more than **10%** of the
 *    samples trip the gate ([inspectClip], `analyse_video`) -- not every frame, which is
 *    what makes a ~5 ms graph cost ~56 ms for a whole clip instead of 1.5 s.
 *
 * ⚠ **This port gates on `nsfw_2` alone.** Upstream votes 2-of-3 across `nsfw_1`, `nsfw_2`
 * and `nsfw_3`, which together are 461 MB against a 266 MB app. A single model cannot
 * reproduce a majority vote, and which way it errs is unmeasured. See docs/roadmap.md 2.
 *
 * ⚠ **A failure to measure is not permission.** `NativePipe.contentScore` returns NaN when
 * the graph did not run, and every verdict below treats NaN as [Verdict.ERROR], never as
 * "allow". A blocking gate that opens when it breaks is worse than no gate, because
 * everything upstream of it believes the content was checked.
 *
 * ## Where the user's protection setting lives
 *
 * The threshold this gate judges against comes from [GuardProfile], which the user
 * controls through the sensitivity slider. The profile is held here, in one place, so
 * that every path -- still, clip, preview, live camera, batch and the remote API --
 * reads the SAME value at the SAME time, and so that changing it takes effect on the
 * next request without a restart.
 *
 * ⚠ [activeProfile] is the single source of truth, and it is **not** the same thing as
 * the profile the UI is drawing. The UI reads the profile the user is choosing; this
 * holds the one that is in force. They are the same object, copied on change.
 */
object ContentGate {

    /**
     * Upstream baseline. The protection control may only tighten this threshold.
     *
     * ⚠ Defined as [GuardProfile.BASE_THRESHOLD] rather than as a literal, so the
     * gate and the policy cannot drift apart about what "upstream's value" means.
     */
    const val THRESHOLD = GuardProfile.BASE_THRESHOLD

    /**
     * The profile in force.
     *
     * ⚠ Volatile because UI and API requests read it on different threads, and a
     * change has to be visible to the NEXT request rather than to the next frame of
     * whatever thread happened to set it. The whole object is replaced, never
     * mutated, so a reader either sees the old profile or the new one -- never a
     * half-updated one.
     */
    @Volatile
    private var activeProfile: GuardProfile = GuardProfile.DEFAULT

    /**
     * The profile in force.
     *
     * The ONE place a caller gets the live configuration from.
     */
    fun profile(): GuardProfile = activeProfile

    /**
     * Apply a new profile.
     *
     * Takes effect on the **next** decision: a request already being judged finishes
     * with the threshold it started with, because changing a cut point halfway
     * through evaluating a score would make the outcome depend on timing.
     *
     * ⚠ The threshold is ALSO pushed to the native pipeline. The native side keeps
     * its own copy (`ffpipe::Config::nsfwThreshold`) and uses it to decide whether
     * the pipeline has been cleared, which is the invariant that lets the
     * frame-changing stages run at all. Without this push the native floor stayed
     * at 0.25 and the sensitivity slider could only ever TIGHTEN the gate.
     */
    fun applyProfile(profile: GuardProfile) {
        val safe = GuardProfileStore.sanitize(
            profile.sensitivity, profile.policyRevision, profile.nsfwMode)
        activeProfile = safe
        pushNativeThreshold(safe)
    }

    /**
     * Push the threshold in force to the native pipeline.
     *
     * ⚠ Safe when nothing is loaded: the native side stores the value and applies
     * it to the next pipeline, so the order of this call and init does not matter.
     */
    private fun pushNativeThreshold(profile: GuardProfile) {
        val t = GuardProfile.thresholdFor(profile.sensitivity, profile.nsfwMode)
        try {
            NativePipe.setNsfwThreshold(t)
        } catch (e: UnsatisfiedLinkError) {
            // The native library is not loaded (the dev build, or a unit test).
            // The Kotlin policy still stands on its own; only the native FLOOR is
            // missing, and the native floor is only consulted by the pipeline.
        }
    }

    /**
     * The Activity, for the diagnostics log.
     *
     * ⚠ Held as a WEAK reference and set once, because the gate is an object and an
     * object outliving the Activity would leak it. The alternative -- passing a
     * Context into every inspect call -- would have meant threading it through six
     * call sites for the sake of a log line, and the six call sites are exactly the
     * ones that would have been forgotten.
     *
     * ⚠ A null context is not an error. The diagnostics log is a convenience; a gate
     * that cannot record a refusal still refuses it.
     */
    private var diagContext: java.lang.ref.WeakReference<Context>? = null

    /** Give the gate a Context so it can write the refusal history. */
    fun attachContext(context: Context) {
        diagContext = java.lang.ref.WeakReference(context.getApplicationContext())
    }

    /**
     * Record a refusal in the diagnostics history.
     *
     * Called from [judge], which is the ONE place a score becomes a decision, so the
     * history covers every path -- still, clip, preview, live camera, batch and the
     * remote API -- without any of them having to remember to.
     */
    private fun recordRefusal(decision: ProtectionDecision, subject: Int) {
        val ctx = diagContext?.get() ?: return
        try {
            ProtectionDiagnostics.record(ctx, decision, subject)
        } catch (e: Exception) {
            // A diagnostics log that cannot be written is not a reason to change a
            // decision. The gate's answer stands on its own.
        }
    }

    /**
     * The threshold currently in force.
     *
     * ⚠ This is the value every path judges against. It is derived from the profile,
     * never set independently, so the slider and the gate cannot disagree.
     */
    fun protectionThreshold(): Float =
        GuardProfile.thresholdFor(activeProfile.sensitivity, activeProfile.nsfwMode)

    /**
     * content_analyser.py:analyse_video -- refuse the video above this percentage.
     *
     * ⚠ Defined as [GuardProfile.VIDEO_RATE_PERCENT] rather than as a literal, so
     * the gate and the policy cannot drift apart about what the allowance is.
     */
    const val VIDEO_RATE_PERCENT = GuardProfile.VIDEO_RATE_PERCENT

    /**
     * content_analyser.py:analyse_video -- one sample per second of footage.
     *
     * ⚠ Public because it is a CONTRACT, not an implementation detail: the live
     * camera promises the same one-check-per-second rate, and a test pins both to
     * the same number.
     */
    const val SAMPLE_INTERVAL_US = 1_000_000L

    /**
     * How far the W8A16 gate's score sits from the fp32 one, measured over 16 held-out
     * frames: +0.075 mean, +0.153 max, **16 of 16 in the same direction**. Only the lower
     * tiers use it -- the fp32 context will not finalize below v79.
     *
     * Reported, never subtracted. Compensating would be a correction constant nobody has
     * tested against content near the threshold, and it would hide the divergence.
     */
    const val QUANTISED_BIAS = 0.087f

    enum class Verdict { ALLOW, BLOCK, ERROR }

    data class Result(
        val verdict: Verdict,
        /** The decision statistic, or the worst one seen across a video's samples. */
        val score: Float,
        /** Videos only: how many samples were taken and how many were flagged. */
        val sampled: Int = 1,
        val flagged: Int = 0,
        val detail: String = "",
        /**
         * The policy's decision, when this result came through the policy layer.
         *
         * ⚠ Present so that a refusal can name the RULE that produced it, not only
         * say "blocked". A caller that only has a Verdict cannot distinguish "the
         * score was too high" from "the check could not run" -- and those two need
         * different answers from the user.
         */
        val decision: ProtectionDecision? = null,
    ) {
        val refused get() = verdict == Verdict.BLOCK

        /**
         * ⚠ ALLOW and nothing else. Not "not blocked" -- ERROR is not blocked either,
         * and a check that could not run has established nothing. Every caller asks this
         * question and no caller re-derives it from [verdict].
         */
        val permitted get() = verdict == Verdict.ALLOW

        /** The rule identifier that produced this result, when there is one. */
        val ruleId: String get() = decision?.ruleId ?: ""

        /**
         * Whether the user could have changed this outcome with the sensitivity dial.
         *
         * `false` for a mandatory safeguard. Used by the UI to say, honestly, whether
         * a refusal was something the slider could have avoided.
         */
        val adjustable: Boolean get() = decision?.adjustable ?: false
    }

    /**
     * Turn a raw score into a verdict, through the policy.
     *
     * ⚠ The policy is the ONLY place a score becomes a decision. Both the still path
     * and both video paths go through here, so they cannot drift apart on how NaN is
     * treated or on where the threshold comes from.
     *
     * @param subject the `R.string.gate_subject_*` resource the request is about.
     * @param sampled videos only: how many samples were taken.
     * @param flagged videos only: how many of them tripped the gate.
     * @param detail a technical note about a fault. Never user-facing.
     * @param consent whether the responsible-use notice has been accepted.
     */
    private fun judge(
        score: Float,
        subject: Int = R.string.gate_subject_this_frame,
        sampled: Int = 1,
        flagged: Int = 0,
        detail: String = "",
        consent: Boolean = true,
    ): Result {
        val decision = DefaultProtectionPolicy.evaluate(
            ProtectionRequest(
                subject = subject,
                score = score,
                sampled = sampled,
                flagged = flagged,
                detail = detail,
                consentAcknowledged = consent,
            ),
            activeProfile,
        )
        val verdict = when {
            decision.permitted -> Verdict.ALLOW
            decision.reason == ProtectionReason.CHECK_UNAVAILABLE -> Verdict.ERROR
            decision.reason == ProtectionReason.CONSENT_NOT_ACKNOWLEDGED -> Verdict.ERROR
            else -> Verdict.BLOCK
        }
        // ⚠ A refusal is recorded HERE, in the one place a score becomes a decision,
        // so the history covers every path -- still, clip, preview, live camera, batch
        // and the remote API -- without each of them having to remember to.
        //
        // ⚠ An ERROR is recorded too. A check that could not run is a refusal as far
        // as the user is concerned, and it is the case most worth investigating.
        if (!decision.permitted) recordRefusal(decision, subject)
        return Result(verdict, score, sampled, flagged, detail, decision)
    }

    /** `analyse_image`: one check on a still. */
    /**
     * One BGR frame to a verdict. The single place a score becomes a decision, so both the
     * still path and both video paths cannot drift apart on how NaN is treated.
     *
     * @param subject which thing is being checked, for the refusal message and for
     *   the diagnostics record.
     */
    private fun inspectPixels(bgr: ByteArray, w: Int, h: Int, subject: Int): Result {
        val score = NativePipe.contentScore(bgr, w, h)
        val detail = if (score.isNaN()) NativePipe.lastError() else ""
        return judge(score, subject, 1, 0, detail)
    }

    /**
     * One still, checked.
     *
     * @param subject which thing is being checked -- one of the
     *   `R.string.gate_subject_*` resources.
     */
    fun inspectStill(bitmap: Bitmap, subject: Int = R.string.gate_subject_this_frame): Result {
        val soft = bitmap.asArgb8888()
            ?: return Result(Verdict.ERROR, Float.NaN, detail = "cannot read image")
        val px = IntArray(soft.width * soft.height)
        soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
        return inspectPixels(
            NativePipe.argbToBgr(px, soft.width, soft.height), soft.width, soft.height, subject)
    }

    /**
     * `analyse_video`: sample one frame per second, refuse above a 10% flagged rate.
     *
     * Uses [MediaMetadataRetriever] rather than the decode loop the swap itself runs on:
     * the gate has to answer BEFORE any frame is processed or previewed, and seeking to
     * ~11 timestamps is far cheaper than a second full decode. `OPTION_CLOSEST_SYNC`
     * keeps it to keyframes, so this costs seeks, not decoding.
     */
    /**
     * `analyse_video`: sample one frame per second, refuse above a 10% flagged rate.
     *
     * Two samplers, in cost order. [MediaMetadataRetriever] first, because it seeks to
     * keyframes and never decodes a whole clip -- ~11 seeks for a 10 s video. When it
     * yields NOTHING it is not believed, and the decoder runs instead.
     *
     * ⚠ The retriever really does return null for every timestamp on some files this
     * project's own decoder reads happily -- a 1366x2160 four-frame clip among them, under
     * both CLOSEST_SYNC and CLOSEST. Treating that as "unreadable" refused videos the
     * swapper could process. Fail-closed, so never a way through the gate, but a way to be
     * told no about a good file.
     */
    fun inspectClip(
        file: File,
        subject: Int = R.string.gate_subject_target_video,
    ): Result =
        sampleByRetriever(file, subject) ?: sampleByDecoder(file, subject)

    /**
     * @return null when the retriever produced no frames at all, meaning "ask the decoder".
     *         A graph failure is NOT null -- a broken gate is broken in either sampler, and
     *         retrying it would only turn one honest ERROR into two.
     */
    private fun sampleByRetriever(file: File, subject: Int): Result? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null

            var sampled = 0
            var flagged = 0
            var worst = Float.NEGATIVE_INFINITY
            var us = 0L
            // `<=` and the max(): a clip shorter than one second still has to be checked.
            val endUs = maxOf(durationMs * 1000L, 1L)
            while (us <= endUs) {
                val frame = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val res = inspectStill(frame, subject)
                    if (res.verdict == Verdict.ERROR) return res
                    sampled++
                    if (res.refused) flagged++
                    if (res.score > worst) worst = res.score
                }
                us += SAMPLE_INTERVAL_US
            }
            return if (sampled == 0) null else verdictOf(sampled, flagged, worst, subject)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    /** The fallback: the same MediaExtractor + MediaCodec path the swap itself decodes with. */
    private fun sampleByDecoder(file: File, subject: Int): Result {
        var sampled = 0
        var flagged = 0
        var worst = Float.NEGATIVE_INFINITY
        var graphError: Result? = null

        val taken = VideoFrames.sample(file.absolutePath, SAMPLE_INTERVAL_US) { bgr, w, h ->
            if (graphError == null) {
                val res = inspectPixels(bgr, w, h, subject)
                if (res.verdict == Verdict.ERROR) {
                    graphError = res
                } else {
                    sampled++
                    if (res.refused) flagged++
                    if (res.score > worst) worst = res.score
                }
            }
        }
        graphError?.let { return it }
        if (taken == 0 || sampled == 0)
            return Result(Verdict.ERROR, Float.NaN, detail = "no frames could be read")
        return verdictOf(sampled, flagged, worst, subject)
    }

    /**
     * The video rule, through the policy.
     *
     * ⚠ The RATE is what upstream's `analyse_video` refuses on, not the worst score.
     * A clip with one bad second in fifty is allowed; a clip that is bad throughout is
     * not. The score threshold still applies to the worst sample -- that is the
     * adjustable part -- but the clip is refused on the RATE, and the rate is
     * mandatory.
     */
    private fun verdictOf(sampled: Int, flagged: Int, worst: Float, subject: Int): Result {
        val rate = 100.0 * flagged / sampled
        // The per-sample threshold decides which samples COUNT as flagged, so the
        // flagged COUNT already depends on the sensitivity. The RATE allowance does not.
        val decision = DefaultProtectionPolicy.evaluate(
            ProtectionRequest(
                subject = subject,
                score = worst,
                sampled = sampled,
                flagged = flagged,
            ),
            activeProfile,
        )
        val verdict = when {
            decision.permitted -> Verdict.ALLOW
            decision.reason == ProtectionReason.CHECK_UNAVAILABLE -> Verdict.ERROR
            else -> Verdict.BLOCK
        }
        return Result(
            verdict, worst, sampled, flagged,
            "%d/%d flagged (%.1f%%)".format(flagged, sampled, rate),
            decision,
        )
    }

    /**
     * The user-facing sentence for a verdict.
     *
     * @param what one of the `R.string.gate_subject_*` resources -- a RESOURCE, not a
     *   string, so a caller cannot pass a sentence fragment that was never translated.
     *
     * ⚠ The two branches must stay distinguishable in every language. BLOCK is a refusal;
     * ERROR is a fault, and translating it into something that reads like a refusal blames
     * the user for our own failure. That is the one thing to check when reviewing a
     * translation of this file's strings.
     *
     * The decision statistic is NOT in the BLOCK sentence. It goes to the log, where it is
     * useful for debugging; aimed at the person holding the phone it is only noise wrapped
     * around a refusal, and a threshold is an invitation to work out how to sit under it.
     *
     * ⚠ The REASON is included for ERROR, unlike BLOCK. `detail` already carried the native
     * error -- "content gate: <qnn error>" -- and nothing ever showed it, so a field report
     * of this failure said only that it happened. That cost a day of guessing at a bug the
     * app could name itself. A refusal must not leak a threshold; a FAULT should say what
     * broke.
     */
    fun message(ctx: Context, what: Int, res: Result): String = when (res.verdict) {
        Verdict.BLOCK -> blockedSentence(ctx, what, res)
        Verdict.ERROR -> ctx.getString(R.string.gate_error, ctx.getString(what)).let {
            if (res.detail.isNotBlank())
                // The detail is a native error string. It stays as it came: it is for the
                // bug report, and a translated QNN message matches nothing anyone can search.
                ctx.getString(R.string.gate_error_detail, it, res.detail)
            else it
        }
        Verdict.ALLOW -> ""
    }

    /**
     * The sentence for a refusal, with the mandatory/adjustable distinction in it.
     *
     * ⚠ The two sentences must stay distinguishable in every language. A refusal the
     * user could have avoided by moving the slider is a DIFFERENT situation from one
     * the slider could never have avoided, and the whole point of the distinction is
     * that the user is told which they are looking at. A translation that collapses
     * them into one sentence destroys the feature.
     */
    private fun blockedSentence(ctx: Context, what: Int, res: Result): String {
        val subject = ctx.getString(what)
        return if (res.adjustable)
            ctx.getString(R.string.gate_blocked_adjustable, subject)
        else
            ctx.getString(R.string.gate_blocked, subject)
    }

    /**
     * The English sentence, for the HTTP API.
     *
     * `ApiServer` answers a machine. A script that matches on an error must not have it
     * change when the phone's owner changes their language, so the API asks for its message
     * through a Context pinned to English -- which keeps ONE copy of these sentences, in
     * strings.xml, rather than a second hardcoded set that can drift from it.
     */
    fun messageEnglish(ctx: Context, what: Int, res: Result): String {
        val cfg = android.content.res.Configuration(ctx.getResources().configuration)
        cfg.setLocale(java.util.Locale.ENGLISH)
        return message(ctx.createConfigurationContext(cfg), what, res)
    }

    /**
     * A refusal, as opposed to a fault.
     *
     * Thrown so the UI can present it as the finished, user-facing sentence it already is,
     * rather than prefixing it with "Failed:" the way a genuine error deserves.
     *
     * @param decision the policy's decision, when there was one. Carried so that the
     *   catch site can write a diagnostics record -- the rule identifier and the
     *   reason -- without having to re-derive them from the message.
     */
    class Refused(message: String, val decision: ProtectionDecision? = null) : Exception(message)
}
