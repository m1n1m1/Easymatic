package com.example.ottomatic.core.service

/**
 * The media player somebody is actually listening to — the two `action.media_*` nodes,
 * `trigger.media_playback`, `value.media_playing` and `value.now_playing`.
 *
 * Reached from `ExecutionContext` and nothing else, on [Microphone]'s and [Files]'
 * reasoning. Its own facade rather than members on [SystemServices] or [DeviceState]
 * because it straddles them: [control] and [seek] change something, [nowPlaying] and
 * [isPlaying] read something, and those two facades are deliberately write-only and
 * read-only. Splitting one subject across both would put it in two places.
 *
 * **This is another app's player, not this app's sound.** `action.play_sound` makes a noise
 * Ottomatic owns and can stop because it started it; nothing here owns anything. Every
 * member is a request to whichever app published a media session, and that app may ignore
 * it, may have gone away between the read and the call, or may implement half of the
 * transport controls and no more.
 *
 * **Nothing throws.** The platform's answer comes through
 * `MediaSessionManager.getActiveSessions`, which raises `SecurityException` when
 * notification access is off — a state the user can be in at any moment, since the grant is
 * revoked from a Settings page this app never sees. So the reads answer **null** and the
 * calls answer a [MediaOutcome] carrying a sentence, and a node reports and pulses `out`
 * rather than unwinding a run.
 *
 * **[isPlaying] answers `Boolean?` rather than `Boolean`**, and that is the whole of why
 * `value.media_playing` can be trusted. Null is "could not read", false is "nothing is
 * playing", and collapsing them would make a revoked grant indistinguishable from silence —
 * so a macro reading "no music is on" would run its branch on a phone that is playing
 * loudly. Null contributes no item, and the comparison downstream fails closed instead.
 *
 * **Which session a blank `app` means** is one rule shared by every member: the first
 * session reported as *playing*, and failing that the highest-priority session, which is
 * the one holding media-button focus. Both halves earn their place — the first is what
 * "pause the music" means when a podcast app is also alive but paused, and the second is
 * what makes [MediaCommand.PLAY] reach the last player used when nothing is playing at all.
 */
interface Media {

    /**
     * What is playing, or null when nothing is — and also null when the read failed.
     *
     * The two are not told apart, and that is honest rather than lossy: "nothing is
     * playing" has no title, no artist and no app to report either, so there is no answer
     * the distinction would let this give.
     *
     * [NowPlaying.playing] is what separates a paused track from a running one. A paused
     * player still answers here on purpose — "what was I listening to?" has an answer while
     * the notification is still up, and losing it the instant somebody hits pause would
     * make the value node useless to the macro that runs *after* a pause.
     */
    fun nowPlaying(app: String = ""): NowPlaying?

    /** Whether any player is playing. **Null, not false, when the read failed.** */
    fun isPlaying(): Boolean?

    /**
     * Sends [command] to the chosen player, answering what happened.
     *
     * Suspending though the platform call is not: the session lookup is a binder round trip,
     * and the implementation moves it off whatever thread the executor is on — the courtesy
     * every other facade extends.
     *
     * [MediaOutcome.changed] means **the command was delivered**, not that the player obeyed
     * it. There is no acknowledgement to wait for — transport controls are one-way — so
     * claiming more would be inventing it.
     */
    suspend fun control(command: MediaCommand, app: String = ""): MediaOutcome

    /**
     * Moves the playing position, answering where it ended up.
     *
     * Separate from [control] rather than a seventh [MediaCommand], because it is the only
     * one that takes a number: folding it in would put a "Seconds" field on a node where
     * five commands out of six ignore it.
     */
    suspend fun seek(mode: SeekMode, seconds: Int, app: String = ""): MediaOutcome
}

/**
 * A transport command.
 *
 * The six a media session actually publishes, and no more. There is deliberately no
 * `SHUFFLE` or `REPEAT`: those are session *modes* rather than transport commands, most
 * players do not expose them at all, and a command that silently does nothing on three apps
 * out of four is worse than one that cannot be expressed.
 */
enum class MediaCommand {
    PLAY,
    PAUSE,

    /**
     * Play if paused, pause if playing.
     *
     * The default, because it is the one command that does the right thing without the
     * macro's author knowing the current state — which is exactly what a headset button or
     * an NFC tag is for.
     */
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    STOP,
}

/**
 * What [Media.seek]'s number means.
 *
 * [FORWARD] and [BACK] are relative and are what people actually want — "skip the ad", "say
 * that again" — while [TO] is absolute and is there for a macro that computed a position.
 * The relative pair needs the player's current position and fails when it publishes none;
 * the absolute one does not, which is the only reason all three exist rather than two.
 */
enum class SeekMode {
    TO,
    FORWARD,
    BACK,
}

/**
 * What one player is playing.
 *
 * [durationMs] and [positionMs] are **-1 when unknown, never 0**, on `RecordingOutcome`'s
 * rule and for a sharper reason here: zero is a perfectly ordinary position for a track to
 * be at, so a zero standing in for "the player does not say" is a lie a comparison cannot
 * see through.
 *
 * [app] is the package name and [appName] the label a person recognises. Both, because the
 * first is what an `action.if` compares against and what the picker stores, and the second
 * is what a notification should print.
 */
data class NowPlaying(
    val app: String = "",
    val appName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val durationMs: Long = -1,
    val positionMs: Long = -1,
)

/**
 * Receipt from a transport command or a seek.
 *
 * The house shape — a `changed` flag and an `error` — plus the one fact only a seek
 * produces. [positionMs] is -1 for everything else, on [NowPlaying]'s rule.
 */
data class MediaOutcome(
    val changed: Boolean = false,
    /** Which player it went to, so a macro can log what it actually reached. */
    val app: String = "",
    val positionMs: Long = -1,
    val error: String = "",
)

/**
 * A change in playback, as the watcher behind `trigger.media_playback` reports it.
 *
 * Separate from [NowPlaying] for the reason `RecordingRecord` is separate from
 * `RecordingOutcome`: a state answers "what is true now" and an event answers "here is what
 * just happened", and [kind] is a fact the state has nowhere to carry.
 */
data class PlaybackChange(
    val kind: PlaybackKind,
    val track: NowPlaying,
)

/** Which change a [PlaybackChange] reports. */
enum class PlaybackKind {
    /** Playback began, from paused or from nothing. */
    STARTED,

    /** Playback paused, with the track still loaded. */
    PAUSED,

    /** Playback ended, or the player went away entirely. */
    STOPPED,

    /**
     * A different track started while playback continued.
     *
     * The one event with no equivalent on `trigger.media_button`, and half the reason this
     * family exists: an album playing through changes track with nobody pressing anything.
     */
    TRACK_CHANGED,
}

/** The engine's default: no player reachable, failing closed with a sentence that says so. */
object NoMedia : Media {
    override fun nowPlaying(app: String): NowPlaying? = null
    override fun isPlaying(): Boolean? = null
    override suspend fun control(command: MediaCommand, app: String) = MediaOutcome(error = UNAVAILABLE)
    override suspend fun seek(mode: SeekMode, seconds: Int, app: String) = MediaOutcome(error = UNAVAILABLE)

    private const val UNAVAILABLE = "Media control is not available on this phone"
}
