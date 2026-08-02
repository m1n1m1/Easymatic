package com.example.ottomatic.engine.transform

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.listDataIn
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.asText
import com.example.ottomatic.domain.registry.LIST_IN
import com.example.ottomatic.domain.registry.LIST_ITEM_TYPE_ID
import com.example.ottomatic.domain.registry.LIST_SLICE_TYPE_ID
import com.example.ottomatic.domain.registry.LIST_SORT_TYPE_ID
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.RawTransform
import com.example.ottomatic.engine.adaptiveTransformNode
import com.example.ottomatic.engine.rawTransformNode
import kotlinx.serialization.Serializable

/**
 * The list side of the graph's pull half: pure functions over a list, none of them
 * with an execution position.
 *
 * All of them take the list on a declared [ANY_LIST][com.example.ottomatic.domain.model.ANY_LIST]
 * port rather than on a `@Wired` config property, because a DATA input derived from
 * config can only ever be a scalar — `NodeSchema` rejects anything else outright.
 * That is why they are `RawTransform`s: reading a declared port means reading a raw
 * [Item].
 *
 * They split into two groups by where their output type comes from. The four that
 * answer with a number, a text or a yes/no know it at declaration time and use
 * `rawTransformNode`. The three that answer with an element or another list learn
 * it from whatever is wired in, so they are adaptive and are retyped in
 * `effectivePorts`.
 *
 * Anything asked of an element goes through [Item.asText] — the one renderer the
 * rest of the app already agrees on — so "does this list contain 3" cannot answer
 * differently from the `action.if` next to it.
 */

/** The elements of the item wired into a list port, or empty when it is not a list. */
private fun NodeInput.elements(): List<Any?> = (item(LIST_IN)?.value as? List<*>).orEmpty()

/** The declared element schema of the wired list, or a wildcard when unknown. */
private fun NodeInput.elementSchema(): ItemSchema =
    (item(LIST_IN)?.schema as? ItemSchema.ListSchema)?.element ?: ItemSchema.Wildcard

/** An element rendered the way the rest of the app renders it. */
private fun Any?.elementText(schema: ItemSchema): String = Item(this, schema).asText()

/** A typed DATA output port for a transform whose result type is fixed. */
private fun fixedOut(schema: ItemSchema, label: String): Port =
    Port(TRANSFORM_OUT, PortKind.DATA, Direction.OUT, schema, label = label)

/** The wildcard placeholder port an adaptive list transform declares before retyping. */
private fun adaptiveOut(label: String): Port =
    Port(TRANSFORM_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Wildcard, label = label)

private fun listIn(): Port = listDataIn(LIST_IN.value, label = "List")

// --- Fixed-output ------------------------------------------------------------

/** `transform.list_count` — how many items a list holds. */
class ListCountTransform : RawTransform<NoConfig> {

    override val definition = rawTransformNode<NoConfig>(
        typeId = "transform.list_count",
        displayName = "Count items",
        description = "How many items are in a list",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.LIST,
        output = fixedOut(ItemSchema.Primitive(Int::class), label = "Count"),
        extraPorts = listOf(listIn()),
    )

    override suspend fun transformItem(config: NoConfig, input: NodeInput, context: ExecutionContext): Item =
        Item(input.elements().size, ItemSchema.Primitive(Int::class))
}

/** Config for `transform.list_join`. */
@Serializable
data class ListJoinConfig(
    @Label("Separator") val separator: String = ", ",
)

/**
 * `transform.list_join` — a list as one piece of text.
 *
 * How a list reaches a notification. n8n calls the same idea Aggregate; the inverse
 * is [SplitTextTransform].
 */
class ListJoinTransform : RawTransform<ListJoinConfig> {

    override val definition = rawTransformNode<ListJoinConfig>(
        typeId = "transform.list_join",
        displayName = "Join list",
        description = "Turns a list into one piece of text, separated by what you choose",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.LIST,
        output = fixedOut(ItemSchema.Primitive(String::class), label = "Text"),
        extraPorts = listOf(listIn()),
    )

    override suspend fun transformItem(config: ListJoinConfig, input: NodeInput, context: ExecutionContext): Item {
        val schema = input.elementSchema()
        val text = input.elements().joinToString(config.separator) { it.elementText(schema) }
        return Item(text, ItemSchema.Primitive(String::class))
    }
}

/** Config shared by the two search transforms. */
@Serializable
data class ListSearchConfig(
    @Label("Item to find") val value: String = "",
)

/** `transform.list_contains` — whether a list holds a given item. */
class ListContainsTransform : RawTransform<ListSearchConfig> {

    override val definition = rawTransformNode<ListSearchConfig>(
        typeId = "transform.list_contains",
        displayName = "List contains",
        description = "Whether a list holds a given item",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.LIST,
        output = fixedOut(ItemSchema.Primitive(Boolean::class), label = "Found"),
        extraPorts = listOf(listIn()),
    )

    override suspend fun transformItem(
        config: ListSearchConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Item = Item(input.indexOf(config.value) >= 0, ItemSchema.Primitive(Boolean::class))
}

/**
 * `transform.list_index_of` — where an item sits in a list, or −1.
 *
 * −1 rather than a failure, because "is it in there and where?" is one question and
 * a fallback field would make the caller answer it twice.
 */
class ListIndexOfTransform : RawTransform<ListSearchConfig> {

    override val definition = rawTransformNode<ListSearchConfig>(
        typeId = "transform.list_index_of",
        displayName = "Position in list",
        description = "Where an item sits in a list, counting from 0, or -1 when it is not there",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.LIST,
        output = fixedOut(ItemSchema.Primitive(Int::class), label = "Position"),
        extraPorts = listOf(listIn()),
    )

    override suspend fun transformItem(
        config: ListSearchConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Item = Item(input.indexOf(config.value), ItemSchema.Primitive(Int::class))
}

/** Shared by contains and index-of, so the two can never disagree. */
private fun NodeInput.indexOf(wanted: String): Int {
    val schema = elementSchema()
    return elements().indexOfFirst { it.elementText(schema) == wanted }
}

// --- Input-typed -------------------------------------------------------------

/** Config for `transform.list_item`. */
@Serializable
data class ListItemConfig(
    @Label("Position (0 is the first)") val index: Int = 0,
)

/**
 * `transform.list_item` — one element of a list, by position.
 *
 * Unreal's array `Get`. Its output carries the wired list's element type, resolved
 * in `effectivePorts`, so reading a list of numbers gives a number port.
 *
 * A position past the end reads as nothing rather than throwing: like
 * `transform.json_read`, this is a pure read on the pull side with no execution
 * wire to route a failure down, so the consumer falls back to its own form value.
 */
class ListItemTransform : RawTransform<ListItemConfig> {

    override val definition = adaptiveTransformNode<ListItemConfig>(
        typeId = LIST_ITEM_TYPE_ID.value,
        displayName = "Item at position",
        description = "One item out of a list, by its position",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.LIST,
        output = adaptiveOut(label = "Item"),
        extraPorts = listOf(listIn()),
    )

    override suspend fun transformItem(
        config: ListItemConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Item? {
        val schema = input.elementSchema()
        val value = input.elements().getOrNull(config.index) ?: return null
        return Item(value, schema)
    }
}

/** Which end a sort starts from. */
@Serializable
enum class SortDirection {
    @Label("A to Z / smallest first")
    ASCENDING,

    @Label("Z to A / largest first")
    DESCENDING,
}

/** How a sort compares two items. */
@Serializable
enum class SortAs {
    @Label("Text")
    TEXT,

    @Label("Number")
    NUMBER,
}

/** Config for `transform.list_sort`. */
@Serializable
data class ListSortConfig(
    @Label("Order") val direction: SortDirection = SortDirection.ASCENDING,
    @Label("Compare as") val compareAs: SortAs = SortAs.TEXT,
)

/**
 * `transform.list_sort` — the same list, reordered.
 *
 * "Compare as" is a real choice rather than a guess: `"10"` sorts before `"9"` as
 * text and after it as a number, and both are right depending on what the list is.
 * An element that will not read as a number sorts as 0 rather than failing the
 * whole list.
 */
class ListSortTransform : RawTransform<ListSortConfig> {

    override val definition = adaptiveTransformNode<ListSortConfig>(
        typeId = LIST_SORT_TYPE_ID.value,
        displayName = "Sort list",
        description = "The same list, in order",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.LIST,
        output = adaptiveOut(label = "List"),
        extraPorts = listOf(listIn()),
    )

    override suspend fun transformItem(config: ListSortConfig, input: NodeInput, context: ExecutionContext): Item {
        val schema = input.elementSchema()
        val sorted = when (config.compareAs) {
            SortAs.TEXT -> input.elements().sortedBy { it.elementText(schema) }
            SortAs.NUMBER -> input.elements().sortedBy { it.elementText(schema).toDoubleOrNull() ?: 0.0 }
        }
        val ordered = if (config.direction == SortDirection.DESCENDING) sorted.reversed() else sorted
        return Item(ordered, ItemSchema.ListSchema(schema))
    }
}

/** Config for `transform.list_slice`. */
@Serializable
data class ListSliceConfig(
    @Label("From position (0 is the first)") val from: Int = 0,
    @Label("How many") val count: Int = 1,
)

/**
 * `transform.list_slice` — a run of items out of a list.
 *
 * Out-of-range ends are clamped rather than refused, which is what makes "the first
 * three" work on a list that turned out to have two.
 */
class ListSliceTransform : RawTransform<ListSliceConfig> {

    override val definition = adaptiveTransformNode<ListSliceConfig>(
        typeId = LIST_SLICE_TYPE_ID.value,
        displayName = "Part of list",
        description = "A run of items out of a list",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.LIST,
        output = adaptiveOut(label = "List"),
        extraPorts = listOf(listIn()),
    )

    override suspend fun transformItem(config: ListSliceConfig, input: NodeInput, context: ExecutionContext): Item {
        val elements = input.elements()
        val start = config.from.coerceIn(0, elements.size)
        val end = (start + config.count.coerceAtLeast(0)).coerceAtMost(elements.size)
        return Item(elements.subList(start, end).toList(), ItemSchema.ListSchema(input.elementSchema()))
    }
}
