package com.example.ottomatic.engine

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightCommandResult
import com.example.ottomatic.core.service.LightRead
import com.example.ottomatic.core.service.LightReading
import com.example.ottomatic.core.service.SceneRecall
import com.example.ottomatic.core.service.SceneResult
import com.example.ottomatic.core.service.SmartHome

/**
 * A recording [SmartHome] for node tests, in [FakeMail]'s shape.
 *
 * The interesting half is the failure fields. Every light node's contract is "report
 * it and pulse `out`, never throw", and that is the part a test has to be able to
 * force — a bridge that has been unplugged, or whose certificate stopped matching,
 * is not something a JVM test can arrange.
 */
class FakeSmartHome(
    var applyFailure: String? = null,
    var recallFailure: String? = null,
    var readFailure: String? = null,
    var reading: LightReading = LightReading(found = true, name = "Lamp", on = true),
    /**
     * What a successful [apply] reports.
     *
     * False with no failure is the "only lights already on, and none of them were"
     * answer — a real outcome that is not an error, and one nothing else in this
     * fake could produce.
     */
    var changed: Boolean = true,
) : SmartHome {

    val applied = mutableListOf<LightCommand>()
    val recalled = mutableListOf<SceneRecall>()
    val read = mutableListOf<LightRead>()

    override suspend fun apply(command: LightCommand): LightCommandResult {
        applied += command
        return applyFailure
            ?.let { LightCommandResult(changed = false, error = it) }
            ?: LightCommandResult(changed = changed)
    }

    override suspend fun recall(request: SceneRecall): SceneResult {
        recalled += request
        return recallFailure
            ?.let { SceneResult(changed = false, error = it) }
            ?: SceneResult(changed = true)
    }

    override suspend fun read(request: LightRead): LightReading {
        read += request
        return readFailure?.let { LightReading(found = false, error = it) } ?: reading
    }
}
