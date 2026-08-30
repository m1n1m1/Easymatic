package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.MediaCommand
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.MediaControlState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The transport commands as the form offers them.
 *
 * A second enum beside [MediaCommand], on `RecordingQualityChoice`'s precedent and for its
 * reason: `@Label` lives in `:node-api` and `core` may not import it, so a facade enum can
 * only ever be shown through `prettify` — which renders `PLAY_PAUSE` as "Play pause". The
 * duplication buys the six labels a person actually reads, and the mapping below is the
 * whole of it.
 */
@Serializable
enum class MediaCommandChoice {
    @Label("Play")
    @SerialName("play")
    PLAY,

    @Label("Pause")
    @SerialName("pause")
    PAUSE,

    @Label("Play or pause")
    @SerialName("play_pause")
    PLAY_PAUSE,

    @Label("Next track")
    @SerialName("next")
    NEXT,

    @Label("Previous track")
    @SerialName("previous")
    PREVIOUS,

    @Label("Stop")
    @SerialName("stop")
    STOP,
}

internal fun MediaCommandChoice.toFacade(): MediaCommand = when (this) {
    MediaCommandChoice.PLAY -> MediaCommand.PLAY
    MediaCommandChoice.PAUSE -> MediaCommand.PAUSE
    MediaCommandChoice.PLAY_PAUSE -> MediaCommand.PLAY_PAUSE
    MediaCommandChoice.NEXT -> MediaCommand.NEXT
    MediaCommandChoice.PREVIOUS -> MediaCommand.PREVIOUS
    MediaCommandChoice.STOP -> MediaCommand.STOP
}

/**
 * Config for `action.media_control`.
 *
 * [app] is **[PickerKind.APP_FILTER] rather than [PickerKind.APP]**, and the difference is
 * not cosmetic. That kind offers a blank "Any app" option and lists everything installed;
 * the other lists only launchable apps and insists on one. Blank has to be a real answer
 * here — "pause the music" is what nearly everybody means, and they mean it about whatever
 * happens to be playing — and a media app that publishes a session need not have a
 * launcher icon at all.
 *
 * **No new [PickerKind] for "apps that have a session right now"**, tempting as that reads.
 * It would fail the completeness half of this app's own chooser test, the one `@WifiNetwork`
 * turns on: the answer set has to be knowable *while the field is being filled in*, and the
 * app you are writing the macro about is precisely the one that is not playing while you
 * write it. A chooser that could only ever offer whatever happened to be running would make
 * the commonest case unreachable.
 */
@Serializable
data class MediaControlConfig(
    @Label("Command") val command: MediaCommandChoice = MediaCommandChoice.PLAY_PAUSE,
    @Label("App (optional)") @Picker(PickerKind.APP_FILTER) val app: String = "",
)

/**
 * `action.media_control` — plays, pauses or skips whatever is playing.
 *
 * **The node this family exists for.** Easymatic could already set the volume of the media
 * *stream* and make sounds of its own, and neither of those can pause Spotify: a stream is
 * a mixer channel, and muting one leaves the track running silently past the bit you wanted
 * to hear. This talks to the player.
 *
 * **It is a request to another app, and the receipt says only that it was delivered.**
 * Transport controls are one-way — there is no acknowledgement to wait for — so
 * [MediaControlState.changed] means the command reached a session, not that the app obeyed
 * it. A player that implements `PLAY` and ignores `PREVIOUS` is a normal player, and
 * pretending to know the difference would be inventing it.
 *
 * **Nothing playing is not a failure.** A macro that pauses the music when a call arrives
 * runs just as often on a quiet phone as on a loud one, so this reports `changed = false`
 * with a console line and pulses `out` — the family's rule, and `action.record_stop`'s.
 *
 * **[MediaCommand.PLAY_PAUSE] is the default** because it is the one command that needs no
 * knowledge of the current state, which is what a headset button, an NFC tag or a widget is
 * for. The other five exist because a macro that *does* know — "when I get in the car,
 * play" — should not have to toggle and hope.
 */
class MediaControlAction : Action<MediaControlConfig, MediaControlState> {

    override val definition = actionNode<MediaControlConfig, MediaControlState>(
        typeId = "action.media_control",
        displayName = "Control Media",
        description = "Plays, pauses or skips the music, podcast or video that is playing",
        category = NodeCategory.MEDIA,
        icon = NodeIcon.MUSIC,
        output = dataOut<MediaControlState>("state", label = "Result"),
        permissions = listOf(MEDIA_PLAYBACK_ACCESS),
    )

    override suspend fun execute(
        input: MediaControlConfig,
        context: ExecutionContext,
    ): NodeOutput<MediaControlState> = NodeOutput(
        context.reportMedia(
            context.media.control(input.command.toFacade(), input.app.trim()),
            input.command.name.lowercase(),
        ),
    )
}
