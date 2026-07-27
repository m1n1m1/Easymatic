package com.example.ottomatic.domain.model

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.schemaOf

/**
 * Port factory helpers used by node definitions
 * ([com.example.ottomatic.engine.ActionNodeDefinition] and
 * [com.example.ottomatic.engine.TriggerNodeDefinition]).
 *
 * DATA *input* ports are not declared here: they are derived from the `@Wired`
 * properties of a node's config class (see
 * [com.example.ottomatic.domain.registry.NodeSchema]). What remains is the
 * EXECUTION ports, the single typed DATA *output* port ([DataOut]) and the
 * wildcard input used by the two adaptive nodes.
 */

/**
 * The fixed EXECUTION port names. Declared once here so the engine's
 * [com.example.ottomatic.engine.ExecutionRoute] and the design-time port
 * resolution in [com.example.ottomatic.domain.registry.effectivePorts] cannot
 * disagree about what a branch port is called.
 */
object ExecPorts {
    val IN = PortName("in")
    val OUT = PortName("out")
    val TRUE = PortName("true")
    val FALSE = PortName("false")
}

/** EXECUTION input port. */
fun execIn(name: PortName = ExecPorts.IN): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.IN)

/** EXECUTION output port. */
fun execOut(name: PortName = ExecPorts.OUT): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.OUT)

/**
 * A node's typed DATA output port: the port declaration *and* the encoder for
 * the values that flow out of it, in one object.
 *
 * Capturing the reified payload type here is what removes the `encodeData`
 * lambda every node used to repeat: a definition holding a `DataOut<O>` can turn
 * any `O` into a port-keyed [Item] on its own, and the port name is written
 * exactly once.
 */
class DataOut<T : Any> @PublishedApi internal constructor(
    val name: PortName,
    val schema: ItemSchema,
    val label: String,
    val cardinality: Cardinality,
    @PublishedApi internal val encoder: (T) -> Item,
) {
    /** The declaration consumed by [NodeTypeDefinition.ports]. */
    val port: Port = Port(
        name = name,
        kind = PortKind.DATA,
        direction = Direction.OUT,
        schema = schema,
        cardinality = cardinality,
        label = label,
    )

    /** Wraps [value] in the [Item] this port carries. */
    fun encode(value: T): Item = encoder(value)
}

/**
 * Declares a node's typed DATA output port. The port's [ItemSchema] is derived
 * from the `@Serializable` payload type [T].
 */
inline fun <reified T : Any> dataOut(
    name: String,
    label: String = name,
    cardinality: Cardinality = Cardinality.ONE,
): DataOut<T> = DataOut(
    name = PortName(name),
    schema = schemaOf<T>(),
    label = label,
    cardinality = cardinality,
    encoder = { Item.of(it) },
)

/**
 * DATA input port accepting any schema. The documented escape hatch for the two
 * adaptive nodes (`action.if`, `action.break`), whose effective port
 * schemas are resolved at design time by
 * [com.example.ottomatic.domain.registry.effectivePorts]. Ordinary nodes derive
 * their typed data inputs from `@Wired` config properties instead.
 */
fun wildcardDataIn(name: String, label: String = name): Port = Port(
    name = PortName(name),
    kind = PortKind.DATA,
    direction = Direction.IN,
    schema = ItemSchema.Wildcard,
    label = label,
)

/**
 * DATA input port accepting *any struct*, but only a struct — `action.break`'s
 * input, whose whole job is to split one into fields.
 *
 * "Any struct" needs no new schema case: an [ItemSchema.Object] with no fields
 * demands nothing of its source, so width-subtyping accepts every object and
 * rejects everything else. A wildcard would accept a number or a date too, and
 * breaking a date into fields is not a thing — the node would just sprout no output
 * ports and leave the user wondering why.
 *
 * A [ItemSchema.Wildcard] source still connects: an adaptive transform that has not
 * been retyped yet does not know what it produces, and refusing it there would make
 * the order the user wires things in matter.
 */
fun structDataIn(name: String, label: String = name): Port = Port(
    name = PortName(name),
    kind = PortKind.DATA,
    direction = Direction.IN,
    schema = ANY_STRUCT,
    label = label,
)

/** The schema meaning "any object at all" — see [structDataIn]. */
val ANY_STRUCT: ItemSchema = ItemSchema.Object(fields = emptyMap())
