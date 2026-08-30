package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.LaunchOutcome
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.engine.ExecutionContext

/**
 * What the nodes that hand something to *another app* share — `action.launch_app`,
 * `action.open_url`, `action.call`, and the two intent nodes.
 *
 * Written once for the reason [OVERLAY_PERMISSION] and the dialog nodes are written
 * once: the interesting decision here is a decision about the *family*. A launch
 * Android refused must be called the same thing on every one of them, or "it didn't
 * open" would mean something different depending on which node you asked.
 */

/**
 * Permission to draw over other apps, declared by every node that opens another app.
 *
 * The same grant and the same platform rule as the dialog nodes' [OVERLAY_PERMISSION]:
 * the engine runs in a background service, starting an Activity from the background is
 * blocked from Android 10 on, and holding this is precisely the documented exemption.
 * Running a foreground service is *not* one — the engine's notification buys nothing
 * here, which is exactly why these nodes looked like they worked and did not.
 *
 * Kept separate from [OVERLAY_PERMISSION] rather than reused so `rationaleFor` can say
 * which of the two is being asked for: those nodes cannot *ask* the user something,
 * these cannot *open* anything.
 */
internal val LAUNCH_OVERLAY_PERMISSION = PermissionRequirement(
    manifestPermission = null,
    type = PrerequisiteType.OVERLAY,
    rationaleKey = "overlay.launch",
)

/**
 * Writes what [outcome] means for [what] into the run log, and answers whether it
 * opened.
 *
 * Everything here is [LogLevel.ERROR] **except [LaunchOutcome.NoReceiver]**: the node was
 * asked to do one thing and did not do it. That also decides whether the user ever sees
 * it — `RunLogStore` persists INFO and above, and this is a failure that by definition
 * happens while nobody is watching, so a DEBUG line would be gone by the time anybody
 * looked.
 *
 * The exception is the rule read carefully rather than a hole in it. Every other member
 * means *the node was asked to do one thing and did not do it*; `NoReceiver` means *it was
 * done, and probably reached nobody* — a different sentence, and the only one in this table
 * that is not a failure. So it logs at [LogLevel.WARN] and answers **true**. Reporting a
 * broadcast that was genuinely sent as an error would make the one outcome that is merely
 * *suspected* indistinguishable from the four that are known.
 *
 * [LaunchOutcome.Blocked] is the only one whose message has to say what to *do*,
 * because it is the only one the user can fix from Settings. [LaunchOutcome.Refused]
 * deliberately does not: there is no grant that makes a protected broadcast sendable, so
 * naming one would send somebody to a switch that cannot help.
 */
internal fun ExecutionContext.reportLaunch(outcome: LaunchOutcome, what: String): Boolean = when (outcome) {
    LaunchOutcome.Launched -> true

    LaunchOutcome.NoSuchApp -> {
        log("$what is not installed, or has nothing to open", LogLevel.ERROR)
        false
    }

    LaunchOutcome.NoHandler -> {
        log("Nothing on this phone can open $what", LogLevel.ERROR)
        false
    }

    LaunchOutcome.Blocked -> {
        log(
            "Android would not let Easymatic open $what from the background. " +
                "Grant \"Display over other apps\" on this node to allow it.",
            LogLevel.ERROR,
        )
        false
    }

    LaunchOutcome.NoReceiver -> {
        log(
            "Sent $what, but nothing on this phone seems to be listening for it. " +
                "Check the spelling of the action, and that the app you meant is installed.",
            LogLevel.WARN,
        )
        true
    }

    LaunchOutcome.Refused -> {
        log(
            "Android refused to send $what: only the system may broadcast that action.",
            LogLevel.ERROR,
        )
        false
    }
}
