package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.RecordingOutcome
import com.example.ottomatic.core.service.RecordingQuality
import com.example.ottomatic.core.service.RecordingRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.RecordingResultItem
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How much of the sound to keep, as the form asks it.
 *
 * A node-side enum mirroring [RecordingQuality] rather than the facade's own, on
 * `WriteCollision`'s arrangement: the labels and the `@SerialName` values are part of the
 * saved workflow and of the config form, and `core` has no business carrying either.
 */
@Serializable
enum class RecordingQualityChoice {
    @Label("Voice — small file")
    @SerialName("voice")
    VOICE,

    @Label("High — music and detail")
    @SerialName("high")
    HIGH,
}

internal fun RecordingQualityChoice.toFacade(): RecordingQuality = when (this) {
    RecordingQualityChoice.VOICE -> RecordingQuality.VOICE
    RecordingQualityChoice.HIGH -> RecordingQuality.HIGH
}

/**
 * Config for `action.record_audio`.
 *
 * [seconds] is `@Wired` because the interesting macros compute it — "record for as long as
 * the last message was", "record a minute for every hour I have been away" — and because a
 * length is the one field here somebody may well want an `action.if` to decide.
 *
 * The last three fields are `ScreenshotConfig`'s, field for field and deliberately: a
 * recording lands somewhere by the same rules a screenshot does, and somebody who has
 * configured one should not have to learn a second vocabulary for the other.
 */
@Serializable
data class RecordAudioConfig(
    @Label("How long (seconds)")
    @Wired
    val seconds: Int = 10,
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
 * `action.record_audio` — records the room for a set number of seconds.
 *
 * **The node that blocks, and the reason the family has three.** A macro that says "record
 * five seconds, then mail it" is written top to bottom, so this suspends for the whole
 * length and hands the finished file to the next node. A fork would put the mailing on a
 * second branch for nothing the user asked for, and `action.notify`'s fork exists because
 * *the user* may take an unbounded time to answer — a duration typed into this node is not
 * unbounded, it is the thing they typed.
 *
 * "Record until something happens" is the case this cannot express, and that is
 * `action.record_start` with `action.record_stop`, not a mode on here. The two shapes
 * disagree about what their data port means — a length that has elapsed versus a file that
 * does not exist yet — and one port with two meanings is the thing `RecordingResultItem`
 * is written to avoid.
 *
 * **Where it goes is a path, not a media row**, which is the opposite call from
 * `action.screenshot` one family along. See `Microphone`: a photo belongs in the collection
 * every gallery reads, a recording belongs where the mail node can find it. Blank folder
 * means the app's own storage, so the commonest use needs no storage grant at all — only
 * the microphone.
 *
 * **Nothing here halts a macro.** A microphone another app is holding, a revoked grant and
 * an OEM refusal all land on the `state` port with an error and pulse `out`, because the
 * next node is very often the one that says something went wrong.
 */
class RecordAudioAction : Action<RecordAudioConfig, RecordingResultItem> {

    override val definition = actionNode<RecordAudioConfig, RecordingResultItem>(
        typeId = "action.record_audio",
        displayName = "Record Audio",
        description = "Records sound from the microphone for a set time and saves it as a file",
        category = NodeCategory.AUDIO,
        icon = NodeIcon.MICROPHONE,
        output = dataOut<RecordingResultItem>("state", label = "Result"),
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun execute(
        input: RecordAudioConfig,
        context: ExecutionContext,
    ): NodeOutput<RecordingResultItem> {
        if (input.seconds <= 0) {
            val problem = "A recording needs a length of at least one second"
            return NodeOutput(context.reportRecording(RecordingOutcome(error = problem), "Recorded"))
        }
        val result = context.microphone.record(
            RecordingRequest(
                toFolder = input.toFolder.trim(),
                name = input.name.trim(),
                seconds = input.seconds,
                quality = input.quality.toFacade(),
                whenExists = input.whenExists.toWhenExists(),
            ),
        )
        return NodeOutput(context.reportRecording(result, "Recorded"))
    }
}
