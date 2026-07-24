package com.example.ottomatic.domain.model

import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.schemaOf

/**
 * Shared port factory helpers used by node definitions
 * ([com.example.ottomatic.engine.ActionNodeDefinition] and
 * [com.example.ottomatic.engine.TriggerNodeDefinition]) to declare their
 * port sets. Port schemas are derived from the typed data classes in
 * `domain/model/items/` via [schemaOf]. EXECUTION ports carry no schema
 * (it is never consulted).
 */

/** EXECUTION input port. */
fun execIn(name: String = "in"): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.IN)

/** EXECUTION output port. */
fun execOut(name: String = "out"): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.OUT)

/** DATA output port with a schema derived from a `@Serializable` type [T]. */
inline fun <reified T : Any> dataOut(
    name: String,
    cardinality: Cardinality = Cardinality.ONE,
): Port = Port(
    name = name,
    kind = PortKind.DATA,
    direction = Direction.OUT,
    schema = schemaOf<T>(),
    cardinality = cardinality,
)

/**
 * Typed DATA input port. The matching action contract decodes the wired item
 * or its declared static configuration fallback into the action input model.
 */
inline fun <reified T : Any> dataInPort(name: String): Port = Port(
    name = name,
    kind = PortKind.DATA,
    direction = Direction.IN,
    schema = schemaOf<T>(),
)

/**
 * DATA input port accepting any schema. Used by the adaptive nodes
 * (`action.condition`, `action.break`) whose effective port schemas are
 * resolved at design time by
 * [com.example.ottomatic.domain.registry.effectivePorts].
 */
fun wildcardDataIn(name: String): Port = Port(
    name = name,
    kind = PortKind.DATA,
    direction = Direction.IN,
    schema = ItemSchema.Wildcard,
)
