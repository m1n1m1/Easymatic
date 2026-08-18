package com.example.ottomatic.engine

import com.example.ottomatic.core.service.Microphone
import com.example.ottomatic.core.service.RecordingOutcome
import com.example.ottomatic.core.service.RecordingRequest

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
) : Microphone {

    val recorded = mutableListOf<RecordingRequest>()
    val started = mutableListOf<RecordingRequest>()
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

    override fun isRecording(): Boolean = running
}
