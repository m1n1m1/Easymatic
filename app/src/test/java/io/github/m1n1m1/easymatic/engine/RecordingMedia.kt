package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.Media
import io.github.m1n1m1.easymatic.core.service.MediaCommand
import io.github.m1n1m1.easymatic.core.service.MediaOutcome
import io.github.m1n1m1.easymatic.core.service.NowPlaying
import io.github.m1n1m1.easymatic.core.service.SeekMode

/**
 * A recording [Media], on `RecordingMicrophone`'s shape.
 *
 * Every answer is a `var` so a test names the outcome it is about, and every call is
 * recorded so a test can assert what actually reached the facade — which is where the
 * load-bearing assertions live for this family: the node's whole job is to pass the right
 * command and the right app along, and both are only visible from this side.
 *
 * [playing] is deliberately `Boolean?`, mirroring the interface rather than simplifying it,
 * because the case worth pinning is the null one: a value node must answer "I do not know"
 * rather than "nothing is playing" when the grant is missing.
 */
class RecordingMedia(
    var outcome: MediaOutcome = MediaOutcome(changed = true, app = "com.example.player"),
    var playing: Boolean? = false,
    var track: NowPlaying? = null,
) : Media {

    val commands = mutableListOf<Pair<MediaCommand, String>>()
    val seeks = mutableListOf<Triple<SeekMode, Int, String>>()

    override fun nowPlaying(app: String): NowPlaying? = track

    override fun isPlaying(): Boolean? = playing

    override suspend fun control(command: MediaCommand, app: String): MediaOutcome {
        commands += command to app
        return outcome
    }

    override suspend fun seek(mode: SeekMode, seconds: Int, app: String): MediaOutcome {
        seeks += Triple(mode, seconds, app)
        return outcome
    }
}
