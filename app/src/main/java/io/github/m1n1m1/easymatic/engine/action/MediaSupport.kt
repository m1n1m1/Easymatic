package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.MediaOutcome
import io.github.m1n1m1.easymatic.domain.model.items.MediaControlState
import io.github.m1n1m1.easymatic.engine.ExecutionContext

/**
 * What every node in the media family needs before it can reach a player.
 *
 * Hoisted to one constant on `ScreenshotAction.SCREENSHOT_ACCESSIBILITY`'s reasoning:
 * five declarations of the same requirement are five chances for one of them to drift,
 * and the Permissions screen groups by this value.
 *
 * **The grant is notification access, which is not what it sounds like here.** Android
 * publishes what is playing as a media notification, and the only supported way for a
 * third-party app to enumerate media sessions is
 * `MediaSessionManager.getActiveSessions(ComponentName)` — where the component is an
 * enabled `NotificationListenerService`. `MEDIA_CONTENT_CONTROL` is the permission that
 * sounds right and is signature-level, so no installed app has ever held it.
 *
 * Its **own rationale key** rather than sharing `"notification.listener"` with the message
 * nodes, on the `overlay.dialog` / `overlay.launch` precedent: one switch, and two
 * genuinely different sentences about what it is for. Somebody who granted it to answer
 * WhatsApp messages should not read a card about WhatsApp when they open Pause Music.
 *
 * **Declared on the two value nodes as well**, which is `value.wifi_network`'s precedent
 * and not a breach of "a value must not be badged for the thing it reports being off". The
 * grant is not the subject of the question here — nobody is asking whether notification
 * access is on — it is what makes the read possible at all, and without it those nodes
 * answer null for ever with nothing anywhere saying why.
 */
val MEDIA_PLAYBACK_ACCESS = PermissionRequirement(
    manifestPermission = null,
    type = PrerequisiteType.NOTIFICATION_LISTENER,
    rationaleKey = "media.playback",
)

/**
 * Writes one console line for [outcome] and turns it into the node's receipt.
 *
 * `reportRecording`'s shape and its reason: both media actions log and convert the same
 * way, and two copies would be two places for the wording to drift. [command] is the one
 * field the facade does not carry back — it already knows what it was asked, and the node
 * is what has to say so on the wire.
 *
 * Three cases rather than two, unlike the recording helper. A media command has a silent
 * middle outcome the microphone does not: **there was simply nothing playing**, which is
 * not an error — asking a phone with no player running to pause is a perfectly reasonable
 * thing for a macro to do — but is worth a line, because a `changed = false` with no
 * explanation is what sends somebody looking for a bug in their headphones.
 */
internal fun ExecutionContext.reportMedia(
    outcome: MediaOutcome,
    command: String,
): MediaControlState {
    when {
        outcome.error.isNotBlank() -> log(outcome.error, LogLevel.WARN)
        outcome.changed -> log("Sent $command to ${outcome.app.ifBlank { "the active player" }}")
        else -> log("Nothing is playing, so $command went nowhere")
    }
    return MediaControlState(
        command = command,
        app = outcome.app,
        changed = outcome.changed,
        positionMs = outcome.positionMs,
        error = outcome.error,
    )
}
