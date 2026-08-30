package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.AudioStream
import io.github.m1n1m1.easymatic.core.service.SoundRequest
import io.github.m1n1m1.easymatic.core.service.SoundSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.play_sound`. [uri] only applies when [sound] is
 * [SoundSource.CUSTOM]; the presets follow the device's own defaults.
 *
 * [maxSeconds] exists because a ringtone or alarm is written to keep going
 * until someone answers it — without a cap, using one as a short cue means a
 * sound that outlasts the workflow. [startSeconds] skips into the sound, which
 * is what makes a long file usable as a cue at all.
 *
 * [uri] is a picker rather than a text field and deliberately not `@Wired`: a
 * `@Wired` port is present whether or not `@VisibleWhen` shows the field, so a
 * wire would resurface the hidden property as a bare data input, and a wired
 * value silently losing against a read-only picker showing another sound's name
 * is worse than not offering it at all.
 */
@Serializable
data class PlaySoundConfig(
    @Label("Sound") val sound: SoundSource = SoundSource.NOTIFICATION,
    @Label("Custom sound")
    @Picker(PickerKind.SOUND)
    @VisibleWhen("sound", "custom")
    val uri: String = "",
    @Label("Play through") val stream: AudioStream = AudioStream.NOTIFICATION,
    @Label("Start at")
    @Hint("seconds into the sound")
    val startSeconds: Int = 0,
    @Label("Stop after")
    @Hint("seconds, 0 = play to the end")
    val maxSeconds: Int = DEFAULT_MAX_SECONDS,
    @Label("Wait until finished") val waitForCompletion: Boolean = false,
) {
    /** This config as a playback request, with its seconds in milliseconds. */
    fun request(): SoundRequest = SoundRequest(
        sound = sound,
        uri = uri,
        stream = stream,
        waitForCompletion = waitForCompletion,
        startMs = startSeconds.coerceAtLeast(0) * MILLIS_PER_SECOND,
        maxMs = maxSeconds.coerceAtLeast(0) * MILLIS_PER_SECOND,
    )
}

private const val MILLIS_PER_SECOND = 1_000

/**
 * The cap an unconfigured node plays under. Not zero, because the sounds that
 * need a cap most — a ringtone, an alarm — are the ones that never stop on
 * their own, and a node dropped on the canvas and left alone should not be the
 * one case that misbehaves. Long enough to be heard, short enough to forgive.
 */
private const val DEFAULT_MAX_SECONDS = 30

/**
 * Action for `action.play_sound`. Plays a device default or a chosen sound and
 * pulses `out` — immediately, or once playback has finished when
 * [PlaySoundConfig.waitForCompletion] is set.
 *
 * A chosen sound needs no storage permission: the editor's picker hands back a
 * content URI the app has been granted access to, and takes that grant
 * persistently so it still holds when the engine plays it. A sound that has
 * since been deleted or revoked is skipped and the workflow carries on, like
 * every other action whose platform call can simply fail.
 */
class PlaySoundAction : Action<PlaySoundConfig, Unit> {

    override val definition = effectNode<PlaySoundConfig>(
        typeId = "action.play_sound",
        displayName = "Play Sound",
        description = "Plays a notification, ringtone, alarm or chosen sound",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.MUSIC,
    )

    override suspend fun execute(input: PlaySoundConfig, context: ExecutionContext): NodeOutput<Unit> {
        context.systemServices.playSound(input.request())
        return NodeOutput(Unit)
    }
}
