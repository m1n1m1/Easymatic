package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.LaunchOutcome
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.engine.ExecutionContext

/**
 * What the three nodes that put *another app* on screen share — `action.launch_app`,
 * `action.open_url`, `action.call`.
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
 * Everything here is [LogLevel.ERROR]: the node was asked to do one thing and did not
 * do it. That also decides whether the user ever sees it — `RunLogStore` persists
 * INFO and above, and this is a failure that by definition happens while nobody is
 * watching, so a DEBUG line would be gone by the time anybody looked.
 *
 * [LaunchOutcome.Blocked] is the only one whose message has to say what to *do*,
 * because it is the only one the user can fix from Settings.
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
            "Android would not let Ottomatic open $what from the background. " +
                "Grant \"Display over other apps\" on this node to allow it.",
            LogLevel.ERROR,
        )
        false
    }
}
