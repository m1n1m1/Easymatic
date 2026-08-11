package com.example.ottomatic.nodeapi.wire

import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.anyToJsonElement
import com.example.ottomatic.domain.model.schema.jsonElementToString
import com.example.ottomatic.domain.model.schema.jsonElementToValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One typed value crossing the plugin boundary.
 *
 * ## Nothing but strings, numbers and booleans crosses, and that is the design
 *
 * There is no `Uri`, no `PendingIntent`, no `IBinder`, no `ParcelFileDescriptor`
 * anywhere in this package, and there must never be. That is not an incidental
 * property of having chosen JSON — it is *why* JSON was chosen.
 *
 * A binder call executes in the plugin's process under the plugin's uid, so
 * Ottomatic's own grants — `READ_CONTACTS`, `SEND_SMS`, `CALL_PHONE`,
 * `RECEIVE_SMS`, notification-listener access, `QUERY_ALL_PACKAGES` — are not
 * borrowable by default. They would become borrowable the moment something here
 * carried a `Uri` with `FLAG_GRANT_READ_URI_PERMISSION`, or a `PendingIntent` the
 * plugin could fire as Ottomatic. A plugin does what its own manifest permits and
 * nothing more; adding a capability-bearing type to this file silently repeals
 * that.
 *
 * ## A plugin's struct is a JsonObject, deliberately
 *
 * [schema] round-trips through [SchemaWire], which drops `ItemSchema.Object.kClass`
 * because there is no host class to name. [jsonElementToValue] answers a
 * [ItemSchema.Object] with the [JsonElement] itself, so a plugin struct's
 * [Item.value] *is* a [JsonObject]. Three things follow, and all three are pinned
 * by test:
 *
 *  - `Item.asText()` renders it as compact JSON, because `anyToJsonElement` matches
 *    `is JsonElement` before it ever reaches the serializer route — which is
 *    exactly the documented contract that a struct converted to text can be fed
 *    straight back into `transform.json_read`;
 *  - [Item.flat] is filled from the object's top level here, so `action.if` can
 *    compare a plugin struct field by field with no new machinery;
 *  - `action.break` has to accept a value that is already JSON rather than
 *    insisting on a `kClass` — see `BreakStructAction`.
 */
@Serializable
data class ItemWire(
    val schema: SchemaWire,
    val value: JsonElement,
)

/** A node's inputs: its stored form values, and whatever arrived on its wired ports. */
@Serializable
data class NodeCallWire(
    val config: Map<String, String> = emptyMap(),
    val data: Map<String, ItemWire> = emptyMap(),
)

/** Log levels, mirrored so `core/service/RunLog.kt` stays out of the plugin API. */
@Serializable
enum class LogLevelWire { DEBUG, INFO, WARN, ERROR }

/** One line a plugin asks the host to write to the run log, attributed to its node. */
@Serializable
data class LogLineWire(
    val level: LogLevelWire = LogLevelWire.INFO,
    val message: String = "",
)

/**
 * What an action answers with.
 *
 * [route] names one of the node's declared execution output ports. A route the
 * declaration does not contain is refused at call time rather than pulsed — the
 * host's own `routePort` already `require`s the same thing of first-party nodes,
 * and a plugin is not a reason to relax it.
 */
@Serializable
data class ActionResultWire(
    val data: Map<String, ItemWire> = emptyMap(),
    val route: String = "out",
    val halt: Boolean = false,
    val log: List<LogLineWire> = emptyList(),
)

/**
 * What a value or a transform answers with.
 *
 * A null [item] is a legitimate answer and the one every failure lands on: the
 * consumer falls back to its form value and a comparison fails closed. That is the
 * pull side's existing contract — "answer null rather than throwing" — reached by
 * a new route.
 */
@Serializable
data class ValueResultWire(
    val item: ItemWire? = null,
    val log: List<LogLineWire> = emptyList(),
)

/** One firing of a plugin trigger. */
@Serializable
data class TriggerEventWire(
    val data: Map<String, ItemWire> = emptyMap(),
    val log: List<LogLineWire> = emptyList(),
)

/**
 * This item on the wire, or null when its schema names a primitive the wire has no
 * member for — see [ItemSchema.toWire].
 */
fun Item.toWire(): ItemWire? =
    schema.toWire()?.let { ItemWire(schema = it, value = anyToJsonElement(value, schema)) }

/**
 * This wire value as a graph [Item].
 *
 * [Item.flat] is filled from a top-level JSON object so that a plugin struct is
 * comparable field by field, exactly as a first-party struct built by `Item.of` is.
 */
fun ItemWire.toItem(): Item {
    val itemSchema = schema.toItemSchema()
    return Item(
        value = jsonElementToValue(value, itemSchema),
        schema = itemSchema,
        flat = (value as? JsonObject)
            ?.mapValues { (_, child) -> jsonElementToString(child) }
            .orEmpty(),
    )
}
