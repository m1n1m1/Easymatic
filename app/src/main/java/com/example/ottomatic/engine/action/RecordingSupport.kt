package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.RecordingOutcome
import com.example.ottomatic.domain.model.items.RecordingResultItem
import com.example.ottomatic.engine.ExecutionContext

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
 * [com.example.ottomatic.core.permissions.Permissions.RECORD_AUDIO].
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
