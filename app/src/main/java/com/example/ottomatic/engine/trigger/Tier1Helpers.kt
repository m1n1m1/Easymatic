package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.TriggerNodeDefinition
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Shared single declaration for Tier 1 broadcast-receiver triggers that
 * produce a [SystemState] data item on their `state` DATA output port.
 *
 * @param eventFilterLabel label of the optional `event` config field. When
 *   non-null, an ENUM config field is declared with options
 *   `"any" + eventFilterOptions`; the runtime filter in [systemStateFlow]
 *   treats `"any"` (or a blank value) as "fire on every event".
 */
@Suppress("LongParameterList")
internal fun systemStateDefinition(
    typeId: String,
    displayName: String,
    description: String,
    category: NodeCategory,
    iconKey: String = "bolt",
    eventFilterLabel: String? = null,
    eventFilterOptions: List<String> = emptyList(),
): TriggerNodeDefinition<SystemState> = triggerNode(
    typeId = typeId,
    displayName = displayName,
    description = description,
    category = category,
    iconKey = iconKey,
    dataOutputs = listOf(dataOut<SystemState>("state")),
    configFields = if (eventFilterLabel != null) {
        listOf(
            ConfigField(
                key = CONFIG_EVENT,
                label = eventFilterLabel,
                type = ConfigFieldType.ENUM(options = listOf(DEFAULT_EVENT) + eventFilterOptions),
                defaultValue = DEFAULT_EVENT,
            ),
        )
    } else {
        emptyList()
    },
    encodeData = { state -> mapOf("state" to Item.of(state)) },
)

/**
 * Shared logic for Tier 1 broadcast-receiver triggers that produce a
 * [SystemState] data item. All such triggers listen to [TriggerHost.busEvents],
 * filter by [source] + [triggerType], optionally apply a user-configured event
 * filter, and map the bus event to a typed [SystemState].
 *
 * @param source the [TriggerSource] this trigger listens to.
 * @param triggerType the `triggerType` payload value identifying this trigger.
 * @param node the placed workflow node (for reading config).
 * @param host the trigger host providing the bus.
 * @param eventOptions the set of valid events for config validation. If
 *   `null`, no event filter is applied (the trigger fires on every matching
 *   bus event).
 */
@Suppress("LongParameterList")
internal fun systemStateFlow(
    source: TriggerSource,
    triggerType: String,
    node: WorkflowNode,
    host: TriggerHost,
    eventOptions: List<String>? = listOf("any"),
): Flow<NodeOutput<SystemState>> {
    val base = host.busEvents()
        .filter { it.source == source }
        .filter { it.payload[KEY_TRIGGER_TYPE] == triggerType }

    val filtered = if (eventOptions != null) {
        base.filter { event ->
            val filter = node.config[CONFIG_EVENT]?.takeIf { it.isNotBlank() } ?: DEFAULT_EVENT
            filter == DEFAULT_EVENT || filter == event.payload[KEY_EVENT]
        }
    } else {
        base
    }

    return filtered.map { event ->
        NodeOutput(
            SystemState(
                event = event.payload[KEY_EVENT].orEmpty(),
                detail = event.payload[KEY_DETAIL].orEmpty(),
                timestamp = event.payload[KEY_TIMESTAMP]?.toLongOrNull() ?: event.firedAtEpochMs,
            ),
        )
    }
}

/** Shared constants for Tier 1 trigger payload keys. */
internal const val KEY_TRIGGER_TYPE = "triggerType"
internal const val KEY_EVENT = "event"
internal const val KEY_DETAIL = "detail"
internal const val KEY_TIMESTAMP = "timestamp"
internal const val KEY_PACKAGE_NAME = "packageName"
internal const val CONFIG_EVENT = "event"
internal const val DEFAULT_EVENT = "any"
