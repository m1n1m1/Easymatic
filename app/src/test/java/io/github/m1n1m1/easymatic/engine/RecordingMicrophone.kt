package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.CaptureOutcome
import io.github.m1n1m1.easymatic.core.service.CaptureRequest
import io.github.m1n1m1.easymatic.core.service.Microphone
import io.github.m1n1m1.easymatic.core.service.RecordingOutcome
import io.github.m1n1m1.easymatic.core.service.RecordingRequest

/**
 * A recording [Microphone], on `RecordingImages`' shape.
 *
 * Every answer is a `var` so a test names the outcome it is about, and every call is
 * recorded so a test can assert the facade was reached — or, more often, that it was
 * **not**: a node handed a length of zero must report without opening anything, and "did
 * nothing" is only checkable from this side.
 */
class RecordingMicrophone(
    var outcome: RecordingOutcome = RecordingOutcome(changed = true, path = "Recordings/a.m4a", name = "a.m4a"),
    var startProblem: String = "",
    var running: Boolean = false,
    var captureProblem: String = "",
    var captured: CaptureOutcome = CaptureOutcome(
        base64 = "AAAA",
        mediaType = "audio/wav",
        durationMs = 1_000,
        heard = true,
    ),
) : Microphone {

    val recorded = mutableListOf<RecordingRequest>()
    val started = mutableListOf<RecordingRequest>()
    val captures = mutableListOf<CaptureRequest>()
    val begunCaptures = mutableListOf<CaptureRequest>()
    var endedCaptures: Int = 0
        private set
    var stops: Int = 0
        private set

    override suspend fun record(request: RecordingRequest): RecordingOutcome {
        recorded += request
        return outcome
    }

    override suspend fun start(request: RecordingRequest): String {
        started += request
        return startProblem
    }

    override suspend fun stop(): RecordingOutcome {
        stops++
        return outcome
    }

    override suspend fun capture(request: CaptureRequest): CaptureOutcome {
        captures += request
        return captured
    }

    override suspend fun beginCapture(request: CaptureRequest): String {
        begunCaptures += request
        return captureProblem
    }

    override suspend fun endCapture(): CaptureOutcome {
        endedCaptures++
        return captured
    }

    override fun isRecording(): Boolean = running
}
