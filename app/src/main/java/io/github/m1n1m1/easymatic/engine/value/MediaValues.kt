package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.core.service.NowPlaying
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.NowPlayingItem
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ValueNode
import io.github.m1n1m1.easymatic.engine.action.MEDIA_PLAYBACK_ACCESS
import io.github.m1n1m1.easymatic.engine.valueNode

/**
 * The two pull-side readings of what is playing.
 *
 * Their own file rather than an addition to `DeviceValues.kt`, on `RecordingValue`'s
 * precedent: those read `DeviceState`, and these read the `media` facade, which is a
 * different dependency and a different argument for being on the pull side at all.
 *
 * **Both clear the pull-side bar the same way, and it is `value.wifi_network`'s way rather
 * than `value.ha_state`'s.** There is no socket here and no warm cache — the read is one
 * synchronous binder call into a system service that is always running, with no credential,
 * no network and no timeout. That is what "cheap, repeatable and cannot fail" actually
 * asks for; a push channel was never the requirement, only one way of meeting it.
 *
 * **Both declare notification access**, which is legal for a value since 2026-08 and is
 * right here for the reason it was right there: the grant is not the *subject* of either
 * question, it is what makes the read possible at all. Without it these answer null for
 * ever, and an undeclared grant would leave the Problems panel, the Permissions screen and
 * the node's own card with nothing to say about why. See [MEDIA_PLAYBACK_ACCESS].
 */

/**
 * `value.media_playing` — whether anything is playing right now.
 *
 * **The value half of `trigger.media_playback`**, and the node that makes "when I get home,
 * *if* music is playing, turn the speakers on" one comparison instead of a second macro
 * keeping a variable in step. The trigger answers "tell me when this changes"; this answers
 * "what is it right now?", and a state with only the trigger half forces everybody to build
 * the other one by hand.
 *
 * **It answers null rather than false when it cannot read**, and that is the whole of why a
 * comparison over it can be trusted. False means "nothing is playing"; null means "the grant
 * is off, so I do not know". Collapsing the two would make a revoked permission look exactly
 * like silence, and a macro reading "no music is on" would run its branch on a phone playing
 * loudly. Null contributes no item and the comparison fails closed instead.
 *
 * That is the opposite call from `value.nfc`, which refuses to declare a permission so it
 * is never badged for the radio being off — and the two agree once the question is right.
 * There, the *subject* is whether the radio is on, so a warning about the radio would be a
 * warning about the node working. Here the subject is another app's player, and the grant is
 * only the door.
 */
class MediaPlayingValue : ValueNode<NoConfig, Boolean> {

    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.media_playing",
        displayName = "Media playing",
        description = "Whether music, a podcast or a video is playing right now",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.MUSIC,
        output = dataOut("playing", label = "Playing"),
        permissions = listOf(MEDIA_PLAYBACK_ACCESS),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): Boolean? =
        context.media.isPlaying()
}

/**
 * `value.now_playing` — the track, podcast or video that is playing.
 *
 * **The struct behind [MediaPlayingValue]'s boolean**, and what makes a macro able to say
 * something about the music rather than only react to it: "when I plug the headphones in,
 * notify me what is playing" is one wire from here into `action.notify`.
 *
 * **A paused player still answers**, with [NowPlaying.playing] false. "What was I
 * listening to?" has an answer for as long as the notification is up, and losing it the
 * instant somebody hits pause would make this useless to the macro that runs *after* a
 * pause — which is most of them.
 *
 * **No config, and specifically no app field**, which is the one place this differs from
 * the two actions. A value node may have config — `value.variable` does — but a `val:` read
 * is performed with an *empty* config map (`resolveValueSource`), so a configured value
 * named as an `action.if` source would silently never match. "What is playing" also has one
 * answer regardless of who asks, which is the property every other value node has and the
 * one `value.variable` had to give up to exist at all.
 *
 * Null when nothing is playing and null when the read failed — deliberately not told apart,
 * because "nothing is playing" has no title, no artist and no app to report either, so there
 * is no answer the distinction would let this give.
 */
class NowPlayingValue : ValueNode<NoConfig, NowPlayingItem> {

    override val definition = valueNode<NoConfig, NowPlayingItem>(
        typeId = "value.now_playing",
        displayName = "Now playing",
        description = "The track, podcast or video that is playing, with its title, artist and app",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.MUSIC,
        output = dataOut("track", label = "Track"),
        permissions = listOf(MEDIA_PLAYBACK_ACCESS),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): NowPlayingItem? =
        context.media.nowPlaying()?.toItem()
}

/**
 * The one place the facade's reading becomes the graph's struct.
 *
 * `RecordingRecord.toItem()`'s arrangement, and it exists for the boundary rather than for
 * tidiness: `core` may not import `domain`, so the two shapes cannot be one type however
 * alike they look. Internal rather than private because `trigger.media_playback` builds its
 * own item from the same reading.
 */
internal fun NowPlaying.toItem(): NowPlayingItem = NowPlayingItem(
    app = app,
    appName = appName,
    title = title,
    artist = artist,
    album = album,
    playing = playing,
    durationMs = durationMs,
    positionMs = positionMs,
)
