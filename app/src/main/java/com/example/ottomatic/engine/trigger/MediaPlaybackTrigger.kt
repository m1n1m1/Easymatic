package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.service.NowPlaying
import com.example.ottomatic.core.service.PlaybackChange
import com.example.ottomatic.core.service.PlaybackKind
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.derivedOut
import com.example.ottomatic.domain.model.items.PlaybackEvent
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.action.MEDIA_PLAYBACK_ACCESS
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.media_playback`.
 *
 * A config class of its own rather than the shared [EventFilter], because of the second
 * field. The nullable enum still renders the blank "Any" option [EventFilter] exists for.
 *
 * [app] is [PickerKind.APP_FILTER] for `action.media_control`'s reasons — blank has to mean
 * "any player", and a media app need not be launchable.
 */
@Serializable
data class MediaPlaybackConfig(
    @Label("Event") val event: PlaybackKind? = null,
    @Label("App (optional)") @Picker(PickerKind.APP_FILTER) val app: String = "",
)

/**
 * `trigger.media_playback` — starts when a player starts, pauses, stops or changes track.
 *
 * **Not `trigger.media_button`, and the two overlap less than they read.** That one hears a
 * *key press* — a headset button, a steering-wheel control — and hears nothing at all when
 * somebody taps play inside Spotify, when a track ends and the next begins, or when a call
 * pauses the music. This hears what the player is *doing*, whoever caused it. The button
 * trigger is about the hardware; this is about the music.
 *
 * **[PlaybackKind.TRACK_CHANGED] is the event with no equivalent anywhere else**, and half
 * the reason the node exists: an album playing through changes track with nobody pressing
 * anything, and "log every song I listen to" or "show the title on my watch" is unreachable
 * without it.
 *
 * **Filtered on the source alone, not routed to a node id** — `trigger.recording_saved`'s
 * arrangement rather than `trigger.image_saved`'s, and the difference is per-node state.
 * An image trigger keeps its own high-water mark, so one photo is new to some armed nodes
 * and old to others and the routing has to happen in `data/`. There is no mark here: a
 * track change is equally new to every armed node, and both filters are string comparisons
 * this can run itself.
 *
 * **It still arms something**, which is why there is a handle and a `finally` where
 * `trigger.recording_saved` has neither. The platform listener behind this needs
 * notification access and costs a registration, so it must not be held while nothing wants
 * it — `armMediaWatch` refcounts by node id for exactly that, and the last cancel withdraws
 * it.
 *
 * The `title` projection is a `derivedOut` on `trigger.image_saved`'s reasoning: a
 * notification saying what just started is the commonest thing anybody wires this into, and
 * it should not need an `action.break` first.
 */
class MediaPlaybackTrigger : Trigger<MediaPlaybackConfig, PlaybackEvent> {

    override val definition = triggerNode<MediaPlaybackConfig, PlaybackEvent>(
        typeId = "trigger.media_playback",
        displayName = "Media Playback Changed",
        description = "Starts when music, a podcast or a video starts, pauses, stops or changes track",
        category = NodeCategory.PHONE_MEDIA,
        icon = NodeIcon.MUSIC,
        output = dataOut<PlaybackEvent>("playback", label = "Playback"),
        extraOutputs = listOf(
            derivedOut<String, PlaybackEvent>("title", label = "Title") { it.title },
        ),
        permissions = listOf(MEDIA_PLAYBACK_ACCESS),
    )

    override fun activate(
        config: MediaPlaybackConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<PlaybackEvent>> = flow {
        val watch = host.armMediaWatch(node.id) { message, level -> host.report(node, message, level) }
        val wantedEvent = config.event?.payloadValue
        val wantedApp = config.app.trim()
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.MEDIA_SESSION }
                .filter { wantedEvent == null || wantedEvent == it.payload[KEY_EVENT] }
                .filter { wantedApp.isBlank() || wantedApp == it.payload[KEY_PACKAGE_NAME] }
                .map { event -> NodeOutput(MediaEventCodec.decode(event.payload).toItem(event.timestamp)) }
                .collect { emit(it) }
        } finally {
            watch.cancel()
        }
    }
}

/**
 * How a [PlaybackChange] crosses `TriggerBus` and comes back.
 *
 * `RecordingEventCodec`'s arrangement and its reason: the bus payload is
 * `Map<String, String>`, so a struct has to be flattened and rebuilt, and one object owning
 * both halves is what stops a key being spelled two ways — which produces a port that is
 * silently always empty and which no test catches unless it happens to assert on that exact
 * field.
 *
 * It reuses [KEY_EVENT] and [KEY_PACKAGE_NAME] from `Tier1Helpers` rather than minting its
 * own, because the trigger filters on those two before decoding anything: a second spelling
 * of "event" would make the filter and the item disagree about the same fact.
 */
object MediaEventCodec {

    private const val APP_NAME = "appName"
    private const val TITLE = "title"
    private const val ARTIST = "artist"
    private const val ALBUM = "album"
    private const val PLAYING = "playing"
    private const val DURATION = "durationMs"

    fun encode(change: PlaybackChange): Map<String, String> = mapOf(
        KEY_EVENT to change.kind.payloadValue,
        KEY_PACKAGE_NAME to change.track.app,
        APP_NAME to change.track.appName,
        TITLE to change.track.title,
        ARTIST to change.track.artist,
        ALBUM to change.track.album,
        PLAYING to change.track.playing.toString(),
        DURATION to change.track.durationMs.toString(),
    )

    /**
     * Rebuilds the change.
     *
     * An unreadable [KEY_EVENT] falls back to [PlaybackKind.STOPPED] rather than throwing,
     * on the bus's usual rule that a malformed payload must not unwind a run — and stopped
     * rather than started, because a macro told nothing happened is better off than one
     * told the music began.
     *
     * [NowPlaying.durationMs] falls back to **-1 rather than 0**, on `ImageEventCodec`'s
     * rule: zero is an ordinary duration for a live stream to report, so it cannot also
     * mean "the payload does not say". Position is deliberately absent — it changes every
     * second, so a snapshot of it inside an event would be stale before anything read it.
     */
    fun decode(payload: Map<String, String>): PlaybackChange = PlaybackChange(
        kind = PlaybackKind.entries.firstOrNull { it.payloadValue == payload[KEY_EVENT] }
            ?: PlaybackKind.STOPPED,
        track = NowPlaying(
            app = payload[KEY_PACKAGE_NAME].orEmpty(),
            appName = payload[APP_NAME].orEmpty(),
            title = payload[TITLE].orEmpty(),
            artist = payload[ARTIST].orEmpty(),
            album = payload[ALBUM].orEmpty(),
            playing = payload[PLAYING].toBoolean(),
            durationMs = payload[DURATION]?.toLongOrNull() ?: -1,
        ),
    )
}

/**
 * The one place the facade's change becomes the graph's struct.
 *
 * `RecordingRecord.toItem()`'s arrangement, for the boundary rather than for tidiness:
 * `core` may not import `domain`, so the timestamp arrives from the bus event as a
 * `DateTime` the trigger already has and the change itself carries none.
 */
internal fun PlaybackChange.toItem(
    at: com.example.ottomatic.domain.model.schema.DateTime,
): PlaybackEvent = PlaybackEvent(
    event = kind.payloadValue,
    app = track.app,
    appName = track.appName,
    title = track.title,
    artist = track.artist,
    album = track.album,
    playing = track.playing,
    durationMs = track.durationMs,
    timestamp = at,
)
