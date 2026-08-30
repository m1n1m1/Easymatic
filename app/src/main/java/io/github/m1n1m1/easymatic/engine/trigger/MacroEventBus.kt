package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus for engine-internal macro lifecycle events
 * ([TriggerSource.MACRO]).
 *
 * [WorkflowRunner] emits here when a macro is enabled (its [run] starts) and
 * when a macro finishes (a triggered execution completes). Triggers such as
 * `trigger.macro_finished` and `trigger.macro_enabled` subscribe via
 * [TriggerHost.macroLifecycleEvents].
 *
 * `replay = 0` matches [io.github.m1n1m1.easymatic.core.trigger.TriggerBus]: a
 * freshly-subscribed trigger does not replay past macro events.
 */
object MacroEventBus {

    private val _events = MutableSharedFlow<TriggerEvent>(
        replay = 0,
        extraBufferCapacity = DEFAULT_BUFFER,
    )

    val events: SharedFlow<TriggerEvent> = _events.asSharedFlow()

    fun emit(event: TriggerEvent) {
        _events.tryEmit(event)
    }

    /**
     * The `"enabled"` / `"finished"` event for [macroId].
     *
     * Here rather than at either call site because there are now two of them —
     * [io.github.m1n1m1.easymatic.engine.WorkflowRunner] announcing an arm, and
     * [io.github.m1n1m1.easymatic.engine.runFromTrigger] announcing the end of a run —
     * and the payload keys are what `trigger.macro_finished` reads. A second copy
     * that spelled one of them differently would not fail; it would just never
     * match.
     */
    fun macroEvent(macroId: String, event: String): TriggerEvent = TriggerEvent(
        source = TriggerSource.MACRO,
        triggerNodeId = NodeId.BROADCAST,
        payload = mapOf(
            "event" to event,
            "macroId" to macroId,
            "timestamp" to System.currentTimeMillis().toString(),
        ),
    )

    private const val DEFAULT_BUFFER = 64
}
