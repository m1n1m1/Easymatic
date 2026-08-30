package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.RecordingOutcome
import io.github.m1n1m1.easymatic.domain.model.items.RecordingResultItem
import io.github.m1n1m1.easymatic.engine.ExecutionContext

/**
 * What the three recording actions share.
 *
 * `ImageSupport`'s arrangement and its reason: the grant, the quality choice and the one
 * log-and-convert step are the same for every node in the family, and three copies of the
 * `rationaleKey` string is exactly how the Permissions screen ends up with two rows for one
 * capability.
 */

/**
 * The microphone grant, declared by the three actions and by nothing else in the family.
 *
 * `trigger.recording_saved` and `value.recording` deliberately go without — see
 * [io.github.m1n1m1.easymatic.core.permissions.Permissions.RECORD_AUDIO].
 */
internal val RECORD_AUDIO = PermissionRequirement(
    manifestPermission = Permissions.RECORD_AUDIO.manifest,
    type = PrerequisiteType.RUNTIME,
    rationaleKey = "audio.record",
)

/**
 * Writes one console line for [outcome] and turns it into the node's receipt.
 *
 * `reportImageWrite`'s shape, one level simpler because there is no "ask the user" outcome
 * to keep apart. Two levels rather than three: a recording that produced no file always has
 * a reason, so there is no silent third case to log at INFO.
 */
internal fun ExecutionContext.reportRecording(
    outcome: RecordingOutcome,
    what: String,
): RecordingResultItem {
    when {
        outcome.error.isNotBlank() -> log(outcome.error, LogLevel.WARN)
        outcome.changed -> log("$what ${outcome.name.ifBlank { outcome.path }}")
        else -> log("No recording was saved")
    }
    return RecordingResultItem(
        changed = outcome.changed,
        path = outcome.path,
        name = outcome.name,
        durationMs = outcome.durationMs,
        sizeBytes = outcome.sizeBytes,
        error = outcome.error,
    )
}
