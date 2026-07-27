package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.TriggerNodeDefinition
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Config of a Tier 1 trigger that can filter on which event it fires for.
 *
 * [event] is a *nullable* enum: unset means "fire on every event", which is what
 * the old `"any"` sentinel string meant. The config form derives its options
 * from [E]'s entries plus a leading blank "Any" choice, so an invalid filter can
 * no longer be persisted.
 */
@Serializable
data class EventFilter<E : Enum<E>>(
    @Label("Event") val event: E? = null,
)

/**
 * Shared declaration for Tier 1 broadcast-receiver triggers that produce a
 * [SystemState] item on their `state` DATA output port.
 *
 * [C] is the trigger's config class: [EventFilter] of the trigger's own event
 * enum when it supports filtering, or
 * [com.example.ottomatic.domain.model.config.NoConfig] when it does not.
 */
internal inline fun <reified C : Any> systemStateDefinition(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    icon: NodeIcon = NodeIcon.BOLT,
): TriggerNodeDefinition<C, SystemState> = triggerNode(
    typeId = typeId,
    displayName = displayName,
    description = description,
    category = category,
    icon = icon,
    output = dataOut<SystemState>("state", label = "State"),
)

/**
 * Shared runtime for Tier 1 broadcast-receiver triggers. All of them listen to
 * [TriggerHost.busEvents], filter by [source] + [triggerType], optionally keep
 * only the events matching the user-selected [event], and map the bus event to a
 * typed [SystemState].
 *
 * @param event the configured filter, or null to fire on every matching event.
 */
internal fun systemStateFlow(
    source: TriggerSource,
    triggerType: String,
    host: TriggerHost,
    event: Enum<*>? = null,
): Flow<NodeOutput<SystemState>> = host.busEvents()
    .filter { it.source == source }
    .filter { it.payload[KEY_TRIGGER_TYPE] == triggerType }
    .filter { bus -> event == null || event.payloadValue == bus.payload[KEY_EVENT] }
    .map { bus ->
        NodeOutput(
            SystemState(
                event = bus.payload[KEY_EVENT].orEmpty(),
                detail = bus.payload[KEY_DETAIL].orEmpty(),
                timestamp = bus.timestamp,
            ),
        )
    }

/**
 * The moment this event reports, or when the bus saw it.
 *
 * The `data/trigger` receivers hand their timestamp over as text in a
 * `Map<String, String>` payload, so this is the one place that reading is turned
 * back into a [DateTime] — every trigger that fills a `timestamp` field goes
 * through it rather than repeating the parse.
 */
internal val TriggerEvent.timestamp: DateTime
    get() = DateTime(payload[KEY_TIMESTAMP]?.toLongOrNull() ?: firedAtEpochMs)

/**
 * The `event` payload value an enum entry matches. Trigger event enums name
 * their entries after the payload they filter on, so the mapping is mechanical
 * rather than a second list of strings to keep in sync.
 */
internal val Enum<*>.payloadValue: String get() = name.lowercase()

/** Shared constants for Tier 1 trigger payload keys, as emitted by `data/trigger`. */
internal const val KEY_TRIGGER_TYPE = "triggerType"
internal const val KEY_EVENT = "event"
internal const val KEY_DETAIL = "detail"
internal const val KEY_TIMESTAMP = "timestamp"
internal const val KEY_PACKAGE_NAME = "packageName"

/**
 * A radio/mode being switched on or off. Shared by `trigger.airplane_mode`,
 * `trigger.bluetooth`, `trigger.screen` and `trigger.power_save`, whose
 * broadcasts all report the same two payload values.
 */
@Serializable
enum class OnOffEvent {
    ON,
    OFF,
}

/**
 * A peripheral connecting or disconnecting. Shared by
 * `trigger.bluetooth_connect` and `trigger.usb_device`.
 */
@Serializable
enum class ConnectionEvent {
    CONNECTED,
    DISCONNECTED,
}

