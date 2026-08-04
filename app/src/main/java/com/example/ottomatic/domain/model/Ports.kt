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

    /** A loop's per-iteration pulse. See [com.example.ottomatic.engine.LoopAction]. */
    val BODY = PortName("body")

    /** A loop's after-the-last-iteration pulse. */
    val COMPLETED = PortName("completed")

    /** A dialog node's "the user said yes" pulse. See `action.dialog_confirm`. */
    val CONFIRMED = PortName("confirmed")

    /** A dialog node's "the user said no, or dismissed it" pulse. */
    val CANCELLED = PortName("cancelled")

    /** A dialog node's "nobody answered in time" pulse. */
    val TIMED_OUT = PortName("timed_out")

    /**
     * What a loop's two exec outputs are *called* on the card.
     *
     * Wiring the wrong one of the two is the single mistake everybody makes with a
     * loop, and "body"/"completed" only read as obvious to someone who already
     * knows what a loop is. The port *names* stay as they are — they are persisted
     * in saved graphs — so this is a display concern and lives beside them.
     */
    const val BODY_LABEL = "Repeat this"
    const val COMPLETED_LABEL = "When finished"

    /**
     * What a dialog's exec outputs are called on the card, for the same reason the
     * loop's two are glossed: these are *outcomes of a question*, and a bare
     * "confirmed" beside a bare "cancelled" reads as a state the node is in rather
     * than as the branch taken when the user answered.
     */
    const val CONFIRMED_LABEL = "When confirmed"
    const val CANCELLED_LABEL = "When cancelled"
    const val TIMED_OUT_LABEL = "When time runs out"
}

/** EXECUTION input port. */
fun execIn(name: PortName = ExecPorts.IN): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.IN)

/** EXECUTION output port. */
fun execOut(name: PortName = ExecPorts.OUT, label: String = name.value): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.OUT, label = label)

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
    @PublishedApi internal val encoder: (T) -> Item,
) {
    /** The declaration consumed by [NodeTypeDefinition.ports]. */
    val port: Port = Port(
        name = name,
        kind = PortKind.DATA,
        direction = Direction.OUT,
        schema = schema,
        label = label,
    )

    /** Wraps [value] in the [Item] this port carries. */
    fun encode(value: T): Item = encoder(value)
}

/**
 * Declares a node's typed DATA output port. The port's [ItemSchema] is derived
 * from the `@Serializable` payload type [T].
 *
 * [T] may itself be a `List<…>`: `buildSchema` maps `StructureKind.LIST` to
 * [ItemSchema.ListSchema], so `dataOut<List<String>>("values")` is a list port with
 * no further ceremony.
 */
inline fun <reified T : Any> dataOut(
    name: String,
    label: String = name,
): DataOut<T> = DataOut(
    name = PortName(name),
    schema = schemaOf<T>(),
    label = label,
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

/**
 * DATA input port accepting *any list*, but only a list — what `action.for_each`
 * and the list transforms take.
 *
 * The exact counterpart of [structDataIn], built the same way and for the same
 * reason: [ItemSchema.ListSchema] with a [ItemSchema.Wildcard] element demands
 * nothing of the element type, so `isAssignableFrom` accepts every list and
 * rejects every primitive, struct and map. A plain wildcard would accept a number,
 * and a loop over a number is not a thing — the node would iterate nothing and
 * leave the user wondering why.
 */
fun listDataIn(name: String, label: String = name): Port = Port(
    name = PortName(name),
    kind = PortKind.DATA,
    direction = Direction.IN,
    schema = ANY_LIST,
    label = label,
)

/** The schema meaning "any object at all" — see [structDataIn]. */
val ANY_STRUCT: ItemSchema = ItemSchema.Object(fields = emptyMap())

/** The schema meaning "any list at all" — see [listDataIn]. */
val ANY_LIST: ItemSchema = ItemSchema.ListSchema(ItemSchema.Wildcard)
