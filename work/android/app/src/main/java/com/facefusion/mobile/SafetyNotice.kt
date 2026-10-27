package com.facefusion.mobile

import android.content.Context

/**
 * The responsible-use notice, and whether the user has accepted it.
 *
 * ## What this is for
 *
 * The app manipulates faces. Done to a real person without their consent, that is
 * harassment, non-consensual intimate imagery, or fraud -- and it is the single most
 * likely way this software causes real harm. Upstream FaceFusion's licence prohibits
 * it, and this project's own build plan requires an in-app notice with an explicit
 * agreement.
 *
 * So the acknowledgement is **mandatory and one-way**: it is not a preference, it is
 * a precondition for using the app at all, and [ProtectionPolicy] refuses every
 * request until it exists.
 *
 * ## Why it is NOT a setting the user can turn off again
 *
 * A checkbox that can be unchecked is a checkbox nobody reads. The notice is shown
 * once, on first launch, and after that the app records only that it was accepted.
 * There is no way to un-accept it, and there is no slider, switch or preference
 * anywhere in the app that touches it.
 *
 * ⚠ This is deliberately NOT modelled as part of [GuardProfile]. If consent were a
 * field of the profile, it would sit next to the sensitivity dial, and the two would
 * be indistinguishable to a reader -- and the next person to add a control would
 * quite reasonably assume it was adjustable.
 */
object SafetyNotice {

    /** The preferences file. */
    const val FILE = "face_mma_safety_notice"

    private const val KEY_ACKNOWLEDGED = "acknowledged"
    private const val KEY_ACKNOWLEDGED_AT = "acknowledged_at"
    private const val KEY_NOTICE_VERSION = "notice_version"

    /**
     * Which version of the notice was accepted.
     *
     * ⚠ A number, so that the acceptance can be tied to the text that was actually
     * shown. If the notice is ever reworded in a way that changes what it asks, the
     * version goes up and the notice is shown again -- rather than a user being held
     * to a promise made against different words.
     */
    const val NOTICE_VERSION = 1

    /** Whether the user has accepted the notice. */
    fun isAcknowledged(context: Context): Boolean {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_ACKNOWLEDGED, false)) return false
        // An acceptance of an OLDER notice does not satisfy the current one.
        return p.getInt(KEY_NOTICE_VERSION, 0) >= NOTICE_VERSION
    }

    /**
     * Record the acceptance, with the time it happened.
     *
     * @param nowMs the time, passed in rather than read here so that it can be
     *   injected in a test.
     */
    fun acknowledge(context: Context, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACKNOWLEDGED, true)
            .putLong(KEY_ACKNOWLEDGED_AT, nowMs)
            .putInt(KEY_NOTICE_VERSION, NOTICE_VERSION)
            .apply()
    }

    /** When the notice was accepted, or 0 if it never was. */
    fun acknowledgedAt(context: Context): Long {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getLong(KEY_ACKNOWLEDGED_AT, 0L)
    }
}
