package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.ImageWrite
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.items.ImageResultItem
import io.github.m1n1m1.easymatic.engine.ExecutionContext

/**
 * What the image nodes share — the grants they declare, and one reading of a write's
 * outcome.
 *
 * Written once on `ForegroundLaunch`'s reasoning: the interesting decision here is a
 * decision about the *family*. A change Android refused must be called the same thing on
 * every node that can be refused, or "it did not work" would mean something different
 * depending on which one you asked.
 */

/**
 * Reading the pictures on the phone. Declared by every image node, because without it
 * none of them can do anything at all.
 *
 * The **modern** name on every API — `Permission.onApi` substitutes
 * `READ_EXTERNAL_STORAGE` below 33 — so that `PermissionRequirement.key` stays stable and
 * a saved workflow goes on meaning the same thing as the fleet's API floor rises.
 */
internal val MEDIA_READ_PERMISSION = PermissionRequirement(
    manifestPermission = Permissions.READ_MEDIA_IMAGES.manifest,
    type = PrerequisiteType.RUNTIME,
    rationaleKey = "media.read",
)

/**
 * Changing a picture another app saved, at or below API 28.
 *
 * Declared by the four nodes that write, and above API 28 there is genuinely nothing to
 * grant — the platform asks the user per operation instead, which is
 * [MEDIA_CONSENT_OVERLAY_PERMISSION]'s business. `AndroidPermissionChecker.existsOnThisApi`
 * therefore reports it *granted* on a newer phone rather than badging every write node for
 * a switch that does not exist there.
 */
internal val MEDIA_WRITE_PERMISSION = PermissionRequirement(
    manifestPermission = Permissions.WRITE_EXTERNAL_STORAGE.manifest,
    type = PrerequisiteType.RUNTIME,
    rationaleKey = "media.write",
)

/**
 * Permission to draw over other apps, declared by every node that can need Android's
 * confirmation.
 *
 * The same grant and the same platform rule as `LAUNCH_OVERLAY_PERMISSION`: the engine
 * runs in a background service, starting an Activity from the background is blocked from
 * Android 10 on, and holding this is precisely the documented exemption. Running a
 * foreground service is **not** one — the engine's notification buys nothing here.
 *
 * Kept separate from the launch and dialog constants rather than reused so `rationaleFor`
 * can say which of the three is being asked for: those nodes cannot *open* anything, the
 * dialog nodes cannot *ask* the user anything, and these cannot get Android's permission
 * to change a photo somebody else's app saved.
 */
internal val MEDIA_CONSENT_OVERLAY_PERMISSION = PermissionRequirement(
    manifestPermission = null,
    type = PrerequisiteType.OVERLAY,
    rationaleKey = "overlay.media",
)

/** The grants every write-side image node declares, in one list so they cannot drift. */
internal val IMAGE_WRITE_PERMISSIONS = listOf(
    MEDIA_READ_PERMISSION,
    MEDIA_WRITE_PERMISSION,
    MEDIA_CONSENT_OVERLAY_PERMISSION,
)

/**
 * Turns a facade result into the node's receipt, writing one line to the run log.
 *
 * **Three outcomes with three log levels**, and keeping them apart is the whole point:
 *
 * - a **refusal by Android** that nobody could answer is an ERROR naming what to grant,
 *   because it is the only one the user can fix from Settings — `reportLaunch`'s rule;
 * - an ordinary **failure** is a WARN, since the macro carries on;
 * - `changed = false` with no error is **not a failure at all** — it is `SKIP` declining
 *   to overwrite, or a delete of something already gone — so it is an INFO.
 *
 * [what] completes the sentence and is the node's own word, so "Moved" and "Deleted" read
 * as themselves rather than as a generic "changed".
 */
internal fun ExecutionContext.reportImageWrite(
    result: ImageWrite,
    what: String,
): ImageResultItem {
    when {
        result.needsConfirmation -> log(result.error, LogLevel.ERROR)
        result.error.isNotBlank() -> log(result.error, LogLevel.WARN)
        result.changed -> log("$what ${result.image.name.ifBlank { result.image.path }}")
        else -> log("Nothing to do, so no picture was changed")
    }
    return result.toItem()
}

/** The facade's write result as the graph's struct. */
internal fun ImageWrite.toItem(): ImageResultItem = ImageResultItem(
    changed = changed,
    uri = image.uri,
    path = image.path,
    name = image.name,
    width = image.width,
    height = image.height,
    sizeBytes = image.sizeBytes,
    needsConfirmation = needsConfirmation,
    error = error,
)

/**
 * The receipt for a node that was given no picture to act on.
 *
 * Its own function because all four write nodes need it and the sentence has to be the
 * same on each: a blank field is a configuration mistake, so it is an ERROR, and the node
 * still pulses `out` rather than halting the run.
 */
internal fun ExecutionContext.noPictureNamed(verb: String): ImageResultItem {
    val problem = "No picture named, so there is nothing to $verb"
    log(problem, LogLevel.ERROR)
    return ImageResultItem(changed = false, error = problem)
}
