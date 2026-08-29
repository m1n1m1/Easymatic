package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.RecordingRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.record_start`.
 *
 * [maxSeconds] is a limit rather than a length, and it is **not optional and not zero**.
 * A recording nobody stops holds the microphone until the process dies, with the system's
 * recording indicator lit the whole time — and the macro that forgets to stop is not a
 * hypothetical, it is the one whose `action.record_stop` sits on a branch that a condition
 * happened not to take. So the field always has a value, and the honest way to say "no
 * limit" is a large one somebody typed on purpose.
 */
@Serializable
data class RecordStartConfig(
    @Label("Stop by itself after")
    @Hint("seconds")
    @Wired
    val maxSeconds: Int = 300,
    @Label("Quality")
    val quality: RecordingQualityChoice = RecordingQualityChoice.VOICE,
    @Label("Save in this folder")
    @FilePath
    @Wired
    val toFolder: String = "",
    @Label("File name")
    @Wired
    val name: String = "",
    @Label("If one is already there")
    val whenExists: WriteCollision = WriteCollision.KEEP_BOTH,
)

/**
 * `action.record_start` — begins a recording and carries straight on.
 *
 * **The node with no data port**, and that is the whole design of the family rather than an
 * omission. There is nothing to report yet: the file does not exist, it has no length, and
 * whether the recording was any good is not knowable for as long as it runs. A receipt here
 * could only carry a `changed` meaning "began", where the same field on
 * `action.record_audio` and `action.record_stop` means "finished" — and a port whose meaning
 * depends on which node it is attached to is worse than no port. The finished file arrives
 * at `action.record_stop`, or at `trigger.recording_saved` when the recording ends on its
 * own or is stopped from somewhere else.
 *
 * **What it buys over `action.record_audio` is "until", not "long".** "Record while I am in
 * the car", "record until the noise stops", "record until I tap the widget" are all one
 * start, some graph, and a stop — and none of them is a number of seconds anybody knows in
 * advance.
 *
 * **A second start does not take the microphone from the first.** There is one microphone,
 * so a start while something is already running reports that and changes nothing, which is
 * the only outcome that leaves the running macro working. It still pulses `out`: this node
 * halts nothing, and a macro whose recording was already running is usually one that ran
 * twice rather than one that is broken.
 */
class RecordStartAction : Action<RecordStartConfig, Unit> {

    override val definition = effectNode<RecordStartConfig>(
        typeId = "action.record_start",
        displayName = "Start Recording",
        description = "Begins recording sound from the microphone and carries on, " +
            "until Stop Recording or the time limit ends it",
        category = NodeCategory.AUDIO,
        icon = NodeIcon.MICROPHONE,
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun execute(
        input: RecordStartConfig,
        context: ExecutionContext,
    ): NodeOutput<Unit> {
        val problem = if (input.maxSeconds <= 0) {
            "A recording needs a time limit of at least one second"
        } else {
            context.microphone.start(
                RecordingRequest(
                    toFolder = input.toFolder.trim(),
                    name = input.name.trim(),
                    seconds = input.maxSeconds,
                    quality = input.quality.toFacade(),
                    whenExists = input.whenExists.toWhenExists(),
                ),
            )
        }
        if (problem.isBlank()) {
            context.log("Recording, for up to ${input.maxSeconds}s")
        } else {
            context.log(problem, LogLevel.WARN)
        }
        return NodeOutput(Unit)
    }
}
