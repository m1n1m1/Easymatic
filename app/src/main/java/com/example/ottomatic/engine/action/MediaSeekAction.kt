package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.SeekMode
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.MediaControlState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The seek modes as the form offers them.
 *
 * [MediaCommandChoice]'s duplication for its reason, and here the labels do more than tidy
 * up: they say what the number beside them means. "To" and "Forward" alone leave "Move: To,
 * Seconds: 30" reading as a sentence that could go either way.
 */
@Serializable
enum class SeekModeChoice {
    @Label("Forward by")
    @SerialName("forward")
    FORWARD,

    @Label("Back by")
    @SerialName("back")
    BACK,

    @Label("To the position")
    @SerialName("to")
    TO,
}

internal fun SeekModeChoice.toFacade(): SeekMode = when (this) {
    SeekModeChoice.FORWARD -> SeekMode.FORWARD
    SeekModeChoice.BACK -> SeekMode.BACK
    SeekModeChoice.TO -> SeekMode.TO
}

/**
 * Config for `action.media_seek`.
 *
 * [seconds] is `@Wired` because this is the one media field a graph plausibly computes —
 * "skip the length of the ad I measured", "resume where the variable says". The other two
 * are choices a person makes once.
 *
 * The default is thirty seconds forward, which is the jump every podcast app puts on its
 * own button, so the node does something useful before it is configured at all.
 */
@Serializable
data class MediaSeekConfig(
    @Label("Move") val mode: SeekModeChoice = SeekModeChoice.FORWARD,
    @Label("Seconds") @Wired val seconds: Int = 30,
    @Label("App (optional)") @Picker(PickerKind.APP_FILTER) val app: String = "",
)

/**
 * `action.media_seek` — jumps to another position in what is playing.
 *
 * **Its own node rather than a seventh command on `action.media_control`**, and the config
 * form is the argument: this is the only transport operation that takes a number, so
 * folding it in would put a "Seconds" field on a node where five commands out of six ignore
 * it — the kind of form where nobody can tell which fields their choice actually reads.
 * `@VisibleWhen` could hide it, but the two nodes also answer different questions: one
 * changes *whether* something plays, this changes *where*.
 *
 * **Relative is the default and absolute is the escape hatch.** Skipping an ad and hearing
 * a sentence again are what people want; [SeekMode.TO] is for a macro that computed a
 * position, usually out of a variable a previous run stored. The relative pair needs the
 * player to publish its current position and reports that it could not when it does not,
 * where the absolute one always works — which is the only reason there are three modes and
 * not two.
 *
 * **A jump past either end is clamped, not refused.** "Back thirty seconds" ten seconds
 * into a track means the beginning, which is what somebody pressing it expects; failing
 * there would make the node useless at exactly the moment it is most used.
 *
 * It pulses `out` whatever happened, on the family's rule.
 */
class MediaSeekAction : Action<MediaSeekConfig, MediaControlState> {

    override val definition = actionNode<MediaSeekConfig, MediaControlState>(
        typeId = "action.media_seek",
        displayName = "Seek Media",
        description = "Jumps forward, back or to a position in the track or podcast that is playing",
        category = NodeCategory.MEDIA,
        icon = NodeIcon.FAST_FORWARD,
        output = dataOut<MediaControlState>("state", label = "Result"),
        permissions = listOf(MEDIA_PLAYBACK_ACCESS),
    )

    override suspend fun execute(
        input: MediaSeekConfig,
        context: ExecutionContext,
    ): NodeOutput<MediaControlState> = NodeOutput(
        context.reportMedia(
            context.media.seek(input.mode.toFacade(), input.seconds, input.app.trim()),
            "seek ${input.mode.name.lowercase()}",
        ),
    )
}
