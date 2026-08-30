package io.github.m1n1m1.easymatic.domain.model

/**
 * The whole vocabulary another app speaks to Easymatic, written down once.
 *
 * Five things read these constants — the content provider, the broadcast receiver,
 * the consent screen, the App access screen and `docs/EXTERNAL_API.md` — and four of
 * them are on *our* side of a boundary the fifth documents. A string duplicated
 * between them would drift, and the failure would land on a third-party developer
 * as "the documented method name does nothing", which is the one audience that
 * cannot read the source to find out why.
 *
 * **Nothing here is renameable.** Every one of these strings is compiled into
 * somebody else's app, or typed into somebody's shell script, the moment it ships —
 * `NodeTypeId`'s rule for the same reason. [VERSION] exists so a caller can tell an
 * old Easymatic from a new one *before* it discovers a method it wanted is missing.
 *
 * ## The two doors, and why they differ
 *
 * [AUTHORITY] is answered by a `ContentProvider`, which gets `getCallingPackage()`
 * — a package name the system vouches for — and can therefore be authorised against
 * a list the user approved by name. [ACTION_RUN] is answered by a
 * `BroadcastReceiver`, which gets **no sender identity whatsoever**, and is
 * therefore authorised by [EXTRA_TOKEN] alone. The broadcast exists anyway because
 * the callers that need it most — Tasker, Automate, MacroDroid, `adb shell am`, a
 * Termux script — cannot call a content provider at all.
 */
object ApiContract {

    /**
     * Bumped when a method is added or an existing one's answer changes shape.
     * A caller reads it through [METHOD_VERSION], which is answered without any
     * approval: asking what this app can do is not itself a capability, and
     * requiring approval to find out whether approval is worth asking for would be
     * a loop.
     */
    const val VERSION = 1

    /** The content provider's authority. */
    const val AUTHORITY = "io.github.m1n1m1.easymatic.triggers"

    /** `content://io.github.m1n1m1.easymatic.triggers` — what a caller passes to `call()`. */
    const val CONTENT_URI = "content://$AUTHORITY"

    /** Answers [KEY_VERSION]. Needs no approval. */
    const val METHOD_VERSION = "version"

    /**
     * Answers [KEY_PAYLOAD] with an [ApiTriggerListWire] as JSON: every API trigger
     * on the device, its declared inputs and its key.
     *
     * Requires an **approved caller**, and deliberately cannot be unlocked with a
     * token: a token authorises one trigger, where this describes all of them — and
     * a caller with no token has nothing to present anyway, which is the whole
     * reason this method exists. Enumerating the user's macro names is a real
     * disclosure even without the ability to run them, so it is gated on the same
     * approval the running is.
     */
    const val METHOD_LIST = "list"

    /**
     * Starts one trigger. `arg` is the macro id; [EXTRA_NODE_ID], [EXTRA_TOKEN] and
     * the `in.*` inputs travel in the extras.
     *
     * Answers as soon as the run is **under way**, never when it finishes: a macro
     * may contain an `action.delay` or an `action.wait_until`, and a binder call may
     * not sit on either.
     */
    const val METHOD_RUN = "run"

    /** The broadcast the Intent front door listens for. Always explicit — set the package. */
    const val ACTION_RUN = "io.github.m1n1m1.easymatic.action.RUN_MACRO"

    /** Which macro. The provider takes it as `call()`'s `arg` too. */
    const val EXTRA_MACRO_ID = "macroId"

    /**
     * Which `trigger.api` node within that macro.
     *
     * Optional: omitted, a macro with exactly one API trigger runs it, which is what
     * nearly every caller wants and spares a shell script a second UUID. A macro
     * with two is ambiguous and is refused rather than guessed at.
     */
    const val EXTRA_NODE_ID = "nodeId"

    /** The trigger's key. Required on the broadcast path, optional for an approved caller. */
    const val EXTRA_TOKEN = "token"

    /**
     * Prefix for an input value: `in.city` fills the port named `city`.
     *
     * A flat prefix rather than a nested Bundle because the same rule has to be
     * expressible on a shell command line —
     * `am broadcast … --es in.city Vienna` — and because it keeps one parser
     * ([io.github.m1n1m1.easymatic.engine.api.ApiInputs]) serving both doors.
     */
    const val INPUT_PREFIX = "in."

    /** `true` when the call did what was asked. Present on every answer. */
    const val KEY_OK = "ok"

    /** One of the `STATUS_*` constants. Present on every answer. */
    const val KEY_STATUS = "status"

    /** A sentence naming what went wrong, for a developer's log. Never shown to the user by us. */
    const val KEY_MESSAGE = "message"

    /** JSON payload, for the methods that answer with one. */
    const val KEY_PAYLOAD = "payload"

    /** [ApiContract.VERSION], answered by [METHOD_VERSION]. */
    const val KEY_VERSION = "version"

    /**
     * An `Intent` the caller starts with `startActivityForResult` to ask the user
     * for approval, present on a [STATUS_NEEDS_APPROVAL] answer.
     *
     * A plain `Intent` and deliberately **not** a `PendingIntent`: a `PendingIntent`
     * runs as *us*, so the consent screen would see no calling package and could not
     * name the app it is about — which is precisely the thing the user is being
     * asked to judge.
     */
    const val KEY_APPROVAL_INTENT = "approvalIntent"

    /** The run has started. It has not necessarily finished, and may still fail. */
    const val STATUS_STARTED = "started"

    /** The user has not approved this app. [KEY_APPROVAL_INTENT] says how to ask. */
    const val STATUS_NEEDS_APPROVAL = "needs_approval"

    /**
     * Refused. Given for a wrong key **and** for a macro that does not exist, when
     * the caller offered a key rather than an approved identity — telling those two
     * apart would turn this door into an oracle for which macro ids are real.
     */
    const val STATUS_DENIED = "denied"

    /** No such macro, or no such API trigger in it. Only ever told to an approved caller. */
    const val STATUS_NOT_FOUND = "not_found"

    /**
     * The macro exists and the caller is entitled to it, but its switch is off.
     *
     * Its own status rather than folded into [STATUS_NOT_FOUND], because the two
     * call for different things from the caller: one is a stale id to drop, the
     * other is a macro sitting right there in Easymatic that the user can turn on.
     * Saying so discloses nothing — the caller already proved itself.
     *
     * Note this is where an external call parts company with a widget tap and a
     * launcher shortcut, both of which run a disabled macro. Deliberate: the switch
     * means off, and a caller that is not looking at the phone cannot see that it is.
     */
    const val STATUS_DISABLED = "disabled"

    /** The call was malformed — no macro id, an unknown method, an ambiguous macro. */
    const val STATUS_INVALID = "invalid"

    /** Too many calls too quickly. The bucket refills; nothing is revoked. */
    const val STATUS_RATE_LIMITED = "rate_limited"
}
