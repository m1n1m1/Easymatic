package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import kotlinx.serialization.Serializable

/**
 * Config for `action.send_intent` and `action.broadcast_intent`.
 *
 * **One config class for two nodes**, on [ToggleConfig]'s reasoning: every field means the same
 * thing on both, and two identical classes would be two places for a label to drift.
 *
 * Every field is [Wired], which is most of the point of the nodes existing at all: an action
 * worked out by `action.script`, a `content://` URI produced by `action.file_write`, a package
 * read out of a variable. The socket stays hidden until it is switched on, so the common case of
 * typing a literal is unaffected.
 *
 * [dataUri] and [mimeType] are two fields for what `Intent` exposes as a single setter, and
 * `AndroidSystemServices.intentFor` says why that is the safer shape: `setData` clears the type
 * and `setType` clears the data, so keeping them apart until the last moment is what makes the
 * one call that cannot get it wrong possible.
 *
 * ## Why two nodes rather than one with a "Send as" field
 *
 * The rule elsewhere in this app is that the user should not be made to choose between two
 * platform mechanisms — one field, resolved in code. That rule has an antecedent, and here it
 * fails: **there is no code that could resolve it.** `Intent("com.foo.BAR")` resolves through
 * `queryIntentActivities` for an Activity and `queryBroadcastReceivers` for a broadcast, the same
 * string can legitimately match both, and nothing else in this config says which was meant. The
 * only "resolution" available is try-one-then-the-other, which is strictly worse than asking: an
 * action that happens to match an activity would put a UI on screen nobody asked for, and a
 * mistyped one would fall through to `sendBroadcast`, which reports nothing at all — an
 * unfalsifiable success, the exact failure `LaunchOutcome.Blocked` exists to end.
 *
 * Given the mechanism must be *stated*, it is stated as two palette entries rather than a field,
 * and the deciding reason is that **`permissions` is a static property of a definition**. Only
 * the Activity form is subject to the Android 10 background-activity-start block, so only it
 * needs [LAUNCH_OVERLAY_PERMISSION]. One node would either over-declare it — an amber "grant
 * Display over other apps" card on the form and a Problems-panel warning on every broadcast, for
 * a grant that changes nothing there — or under-declare it and silently restore the failure that
 * permission was added to report. That is `value.nfc`'s rule one family over: a node must not be
 * badged for a grant it does not need. After that: "broadcast" is a word somebody types into
 * palette search, and a mode hidden inside a node is invisible to it.
 *
 * The audience is the last part of the argument. Somebody who cannot answer "activity or
 * broadcast?" does not have an intent to send — they arrived with a recipe from a forum post,
 * and that post says which.
 */
@Serializable
data class IntentConfig(
    @Label("Action") @Wired val action: String = "",

    /**
     * Which app to hand it to, or blank to let `PackageManager` decide.
     *
     * [PickerKind.APP_FILTER] rather than [PickerKind.APP], and both halves of that kind's
     * argument are load-bearing here: an app that only registers a receiver has no launcher
     * activity and would be missing from the other list, and blank is a genuine answer — for an
     * activity it means "offer me the chooser", which is often what somebody wants.
     *
     * Naming an app is nonetheless close to mandatory for a broadcast, and `IntentSpec` says so
     * in the console rather than here, because it is Android 8's delivery rule rather than a
     * property of this field.
     */
    @Label("App (optional)") @Picker(PickerKind.APP_FILTER) @Wired val packageName: String = "",

    @Label("Data URI (optional)") @Wired val dataUri: String = "",

    @Label("MIME type (optional)") @Wired val mimeType: String = "",

    @Label("Category (optional)") @Wired val category: String = "",

    @Label("Extras")
    @Hint("one key=value per line, count:int=5 to type one")
    @Multiline
    @Wired
    val extras: String = "",
)
