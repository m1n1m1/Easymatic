package com.example.ottomatic.engine.plugin

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.PluginNodes
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.trigger.TriggerOutput
import com.example.ottomatic.engine.trigger.triggerOutputFrom
import com.example.ottomatic.nodeapi.wire.NodeCallWire
import com.example.ottomatic.nodeapi.wire.PluginJson
import com.example.ottomatic.nodeapi.wire.TriggerEventWire
import com.example.ottomatic.nodeapi.wire.toItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A trigger that lives in another app, as a flow of events.
 *
 * ## Why this needs no lifetime machinery of its own
 *
 * A trigger is the one plugin node kind with a *long-lived* relationship across the
 * process boundary, and the obvious design gives it a reconnection loop: watch for the
 * plugin's process dying, back off, re-arm, cap the attempts. All of that would be a
 * second, parallel arming lifetime sitting underneath the one `WorkflowRunner` already
 * has — and the two would eventually disagree about whether a trigger is armed.
 *
 * So there is none of it. This is a plain `callbackFlow`: arm on collection, disarm in
 * `awaitClose`. That is precisely `Trigger.activate`'s existing contract — not
 * suspending, cold, teardown in a `finally` — which means cancelling the arm scope
 * tears a plugin trigger down exactly as it tears down a first-party one, and nothing
 * in `WorkflowRunner` or `MacroEngineService` changes.
 *
 * **Recovery is re-arming, not resuming.** When a plugin's process dies and comes back,
 * `PluginRegistry` re-reads its declarations and asks the engine for an ordinary
 * `ACTION_REARM_CHANGED` — the same path a moved geofence or a renamed global takes.
 * That path already cancels *and joins* the previous job before starting the next, so
 * the old `awaitClose` has run and the old registration is gone before a new one is
 * made. Reusing it is what keeps "armed" a single fact with a single owner.
 *
 * ## `onStopped` is not the same as quiet
 *
 * A trigger that has stopped for good — hardware absent, a permission the plugin itself
 * was refused — closes the flow and says so in the run log. Without that, a trigger
 * that will never fire again looks exactly like one that has had nothing to report,
 * which is the failure mode this whole integration is most likely to have.
 */
object PluginTriggerBridge {

    /** True when this node is a plugin trigger. */
    fun isPluginTrigger(node: WorkflowNode): Boolean =
        PluginNodes.byId(node.typeId)?.definition?.kind == com.example.ottomatic.domain.model.NodeKind.TRIGGER

    /**
     * The event flow for a plugin trigger, or null when this node is not one.
     *
     * [armId] is minted by the host and only echoed by the plugin, because the same
     * trigger can legitimately be armed more than once — the same macro enabled twice,
     * or a plugin trigger inside two workflows — and a plugin keying its registrations
     * by typeId alone would collapse them onto one.
     */
    fun activate(node: WorkflowNode, context: ExecutionContext): Flow<TriggerOutput>? {
        val entry = PluginNodes.byId(node.typeId)?.takeIf { isPluginTrigger(node) } ?: return null
        val request = PluginJson.encodeToString(
            NodeCallWire.serializer(),
            NodeCallWire(config = node.config.mapKeys { (key, _) -> key.value }),
        )
        return callbackFlow {
            val armId = "${node.id.value}@${armCounter.incrementAndGet()}"
            val armed = entry.channel.armTrigger(
                typeId = entry.definition.typeId.value,
                armId = armId,
                request = request,
                onFired = { eventJson ->
                    // trySend rather than send: this arrives on a binder thread from
                    // another app, which cannot suspend, and a dropped event is better
                    // than a blocked binder thread in somebody else's process.
                    decode(eventJson)?.let { trySend(it) }
                },
                onStopped = { reason ->
                    context.log("${entry.pluginName} stopped this trigger: $reason", LogLevel.WARN)
                    close()
                },
            )
            if (!armed) {
                context.log(
                    "${entry.pluginName} could not arm '${node.name}' — it may have been stopped " +
                        "by the system; it will be armed again when the app comes back",
                    LogLevel.WARN,
                )
                close()
            }
            awaitClose { entry.channel.disarmTrigger(armId) }
        }
    }

    /**
     * Shared with the process API through
     * [com.example.ottomatic.engine.trigger.triggerOutputFrom] — both start a run
     * with data that did not come from the graph, and a second reading of this wire
     * is how the two would eventually disagree about a `DateTime`.
     */
    private fun decode(eventJson: String): TriggerOutput? = triggerOutputFrom(eventJson)

    private val armCounter = java.util.concurrent.atomic.AtomicLong()
}
