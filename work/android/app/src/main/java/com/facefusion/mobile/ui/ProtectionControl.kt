package com.facefusion.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.facefusion.mobile.GuardProfile
import com.facefusion.mobile.NsfwMode
import com.facefusion.mobile.R
import kotlin.math.roundToInt

/**
 * The protection sensitivity control.
 *
 * ONE control, drawn the same way in both places it appears -- the Swap screen and
 * the Settings screen -- so that the number the user set in one is recognisably the
 * same number in the other.
 *
 * ## Why it is a slider and not a switch
 *
 * A switch offers two states and this setting is not two-state. The thing the user
 * is choosing is "how high does the content score have to be before the check
 * refuses the material", and that is a continuum with a meaningful middle: at the
 * low end the app behaves exactly as it did before this control existed, and at the
 * high end it refuses material it is merely unsure about. A switch would have had
 * to pick one of those and call it the other.
 *
 * ## Why the endpoints are labelled
 *
 * "Least sensitive" and "Most sensitive", under the two ends of the track. A slider
 * with two ends and no words is a slider people have to experiment with, and
 * experimenting with a safety control means discovering its meaning by seeing what
 * it refuses -- which is a bad way to find out what it does.
 *
 * ⚠ The LOW end is "least sensitive" and the HIGH end is "most sensitive", and the
 * order of those two labels is a contract: the mapping in
 * [GuardProfile.thresholdFor] is monotonic, so a swapped pair of labels would make
 * the slider invert itself. A test checks the order.
 *
 * ## Why the value is shown as a number
 *
 * Because the number is what the user is actually choosing. "65" is a setting;
 * "mostly strict" is a description. The number is also what appears in the refusal
 * diagnostics, so the two can be matched up.
 */
@Composable
fun ProtectionControl(
    /** The profile in force. The slider shows and moves THIS value. */
    profile: GuardProfile,
    /**
     * Called with the new profile when the user moves the slider.
     *
     * ⚠ The new profile, not a bare float: the caller is expected to persist it and
     * push it into the gate, and giving it the whole profile makes it much harder to
     * do half of that.
     */
    onProfileChange: (GuardProfile) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the "reset to default" button is offered.
     *
     * Offered in Settings, where there is room for it and where a user who has
     * moved the dial and wants it back is most likely to be looking.
     */
    showReset: Boolean = false,
    /** Called when the reset button is pressed. */
    onReset: (() -> Unit)? = null,
) {
    // The slider's scale is 0..100 because that is what a person reads off a
    // track; the profile's scale is 0.0..1.0 because that is what the mapping
    // wants. The conversion happens in exactly two places -- here, on the way out,
    // and in GuardProfile.thresholdFor, on the way in -- so there is no third
    // place for the two scales to be confused.
    val percent = (profile.sensitivity * 100f).roundToInt().coerceIn(0, 100)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.guard_title),
                     style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                // The number, in a monospace-ish label so it does not jitter as the
                // digits change -- a value that moves under the finger is a value
                // people distrust.
                Text(stringResource(R.string.guard_value, percent),
                     style = MaterialTheme.typography.labelLarge)
            }
            Text(stringResource(R.string.guard_summary),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            // The slider's accessibility label, read out by TalkBack.
            //
            // ⚠ Resolved HERE, outside the `semantics` block, because
            // `stringResource` is a @Composable function and the semantics block is
            // not a composable scope. Calling it inside is a compile error, and
            // resolving it outside is also what keeps the label in step with the
            // value: it is recomposed whenever `percent` changes.
            val sliderLabel = stringResource(R.string.guard_slider_cd, percent)

            Slider(
                value = percent.toFloat(),
                onValueChange = { v ->
                    // Round to whole percent: the slider reports a continuous float,
                    // and storing that would give a profile whose stored value is
                    // never the value the user saw.
                    //
                    // ⚠ `copy` keeps the policy revision, so the new profile is
                    // stamped with the SAME revision as the old one. A revision bump
                    // is a recalibration, and moving the dial is not.
                    val p = v.roundToInt().coerceIn(0, 100)
                    onProfileChange(profile.copy(sensitivity = p / 100f))
                },
                valueRange = 0f..100f,
                // ⚠ Continuous (no explicit `steps`). A Slider with steps=99 in Material3
                // forces snap-to-tick behaviour, which on many devices refuses to respond
                // to taps anywhere but the thumb itself ("the indicator doesn't move when
                // I press it"). Continuous lets the user tap anywhere on the track to jump
                // there; onValueChange still rounds to a whole percent, so the stored value
                // remains exact.
                modifier = Modifier.semantics { contentDescription = sliderLabel },
            )

            // The two endpoints, under the two ends of the track. SpaceBetween, so
            // "Least sensitive" sits under the left end and "Most sensitive" under
            // the right one.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.guard_end_low),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     textAlign = TextAlign.Start)
                Text(stringResource(R.string.guard_end_high),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     textAlign = TextAlign.End)
            }

            // What the dial does, and -- just as prominently -- what it does not.
            // The two are separated because a reader who sees only the first will
            // believe the second is still true.
            Text(stringResource(R.string.guard_note),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.guard_note_adjustable),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.guard_note_mandatory),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            // The threshold in force, so the number behind the dial is visible.
            //
            // ⚠ Read from the PROFILE, mode included, so this number is the one the
            // gate is actually judging against. Showing the sensitivity-only
            // mapping here would print 0.250 while the gate was using 2.000.
            Text(
                stringResource(R.string.guard_threshold_now,
                               "%.3f".format(GuardProfile.thresholdFor(
                                   profile.sensitivity, profile.nsfwMode))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (showReset && onReset != null) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.guard_reset))
                }
            }
        }
    }
}

/**
 * The NSFW Mode switch.
 *
 * ⚠ TWO states and a switch, because this setting genuinely IS two-state: the app
 * either refuses adult content or it does not. The sensitivity DIAL is the
 * continuous one, and the two are different kinds of setting -- one is a number
 * within a range, the other is a permission.
 *
 * ## What the switch does NOT do
 *
 * It does not switch the content check off, it does not let a failed check pass,
 * and it does not touch the consent rules, the rules about real people, or the
 * rules about children. Those are mandatory and are enforced identically in both
 * states. The UI says so in as many words, in both states, because a switch that
 * reads as "explicit content allowed" without that sentence would be a claim the
 * app cannot make.
 *
 * ## Where it lives
 *
 * Next to the sensitivity dial, in both places the dial appears. The two settings
 * decide the same number, so a user who has set one and cannot find the other
 * would be left guessing which is in force.
 */
@Composable
fun NsfwModeControl(
    /** The mode in force. */
    mode: NsfwMode,
    /** Apply a new mode. Persist it, push it into the gate, and redraw. */
    onModeChange: (NsfwMode) -> Unit,
    /** The sensitivity in force, so the threshold in force can be shown. */
    sensitivity: Float = GuardProfile.DEFAULT_SENSITIVITY,
    modifier: Modifier = Modifier,
) {
    val on = mode == NsfwMode.ON

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // MMA Face: replace the tiny Switch with two big explicit buttons
            // so it is impossible to miss whether NSFW mode is ON or OFF.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.nsfw_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.nsfw_mode_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(2.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // OFF button: filled when OFF (safe default), outlined when ON.
                    if (!on) {
                        Button(
                            onClick = { onModeChange(NsfwMode.OFF) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.nsfw_mode_off),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onModeChange(NsfwMode.OFF) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text(stringResource(R.string.nsfw_mode_off)) }
                    }
                    // ON button: filled/red when ON (warning), outlined when OFF.
                    if (on) {
                        Button(
                            onClick = { onModeChange(NsfwMode.ON) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.nsfw_mode_on),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onModeChange(NsfwMode.ON) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text(stringResource(R.string.nsfw_mode_on)) }
                    }
                }
            }

            // What the CURRENT state does. Both states have their own sentence,
            // because a single sentence would have to be true of both, and the
            // interesting difference between them IS the sentence.
            Text(
                stringResource(if (on) R.string.nsfw_mode_on_note
                              else R.string.nsfw_mode_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // The threshold in force, so the number behind the switch is visible.
            Text(
                stringResource(R.string.nsfw_mode_threshold,
                               "%.3f".format(GuardProfile.thresholdFor(sensitivity, mode))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // The rules, in BOTH states. ⚠ This is the sentence that stops ON from
            // reading as a permission slip, and it has to be here whether the
            // switch is on or off.
            Text(stringResource(R.string.nsfw_mode_rules),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.nsfw_mode_responsibility),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
