package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.anyToJsonElement
import com.example.ottomatic.domain.model.wildcardDataIn
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Collecting a list across a loop.
 *
 * A loop can act on every item, but its body cannot hand anything back — each pass
 * overwrites the last one's outputs, and nothing in the graph outlives a run except
 * a variable. These two actions are what turn "notify about each of them" into
 * "collect the ones that matched, then notify once".
 *
 * **A list variable is still flat text.** It holds the JSON array — exactly what
 * [Item.asText] already renders a list as, and exactly what `transform.json_read`
 * already parses — so `VariableStore` needs no idea that lists exist, nothing
 * changes on disk, and a list is read back through the same visible node every
 * other loosely-typed value goes through. A typed store would have been a second
 * type system beside `ItemSchema` that only variables used.
 *
 * The empty list is written as `[]` rather than left unset, because "I looked and
 * found none" and "I never ran" are different answers and a comparison should be
 * able to tell them apart.
 */

/** How a malformed or unset variable reads: an empty list, never a failure. */
private const val EMPTY_LIST = "[]"

private val LIST_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** The stored array for [name], or empty when it is unset or holds something else. */
private fun readList(context: ExecutionContext, name: String): List<JsonElement> = runCatching {
    LIST_JSON.parseToJsonElement(context.variables.get(name) ?: EMPTY_LIST) as? JsonArray
}.getOrNull().orEmpty()

/** Config for `action.list_clear`. */
@Serializable
data class ListClearConfig(
    @Label("List variable name") val name: String = "",
)

/**
 * `action.list_clear` — starts a fresh, empty list.
 *
 * Belongs *before* the loop that fills it. Without it a variable keeps whatever the
 * previous run left there and every run appends to the last, which reads as a macro
 * that slowly goes wrong rather than one that is wired wrong.
 */
class ListClearAction : Action<ListClearConfig, Unit> {

    override val definition = effectNode<ListClearConfig>(
        typeId = "action.list_clear",
        displayName = "Empty List",
        description = "Starts a named list over, ready to collect into",
        category = NodeCategory.DATA,
        icon = NodeIcon.LIST,
    )

    override suspend fun execute(input: ListClearConfig, context: ExecutionContext): NodeOutput<Unit> {
        val name = input.name.trim()
        if (name.isEmpty()) {
            context.log("Empty List: no name configured, nothing stored", LogLevel.WARN)
            return NodeOutput(Unit)
        }
        context.variables.set(name, EMPTY_LIST)
        context.log("Empty List: $name")
        return NodeOutput(Unit)
    }
}

/** Config for `action.list_add`. */
@Serializable
data class ListAddConfig(
    @Label("List variable name") val name: String = "",
)

/** The DATA input carrying what to append. */
private val LIST_ADD_VALUE_IN = PortName("value")

/**
 * `action.list_add` — appends one item to a named list variable.
 *
 * A [RawAction] reading a wildcard port rather than an ordinary `@Wired String`,
 * and that is the whole point: a `@Wired` property arrives already flattened
 * through `asText()`, so a number would be stored as `"3"` and a struct as a quoted
 * blob of its own JSON. Taking the raw [Item] lets [anyToJsonElement] put it in the
 * array with its type intact — a number stays a number, a struct stays an object —
 * which is what makes reading the list back as numbers work.
 */
class ListAddAction : RawAction<ListAddConfig> {

    override val definition = effectNode<ListAddConfig>(
        typeId = "action.list_add",
        displayName = "Add to List",
        description = "Appends an item to a named list, usually from inside a loop",
        category = NodeCategory.DATA,
        icon = NodeIcon.LIST,
        extraPorts = listOf(wildcardDataIn(LIST_ADD_VALUE_IN.value, label = "Item")),
    )

    @Suppress("ReturnCount") // Two guards and the result; the alternative is nesting.
    override suspend fun executeRaw(
        config: ListAddConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        val name = config.name.trim()
        if (name.isEmpty()) {
            context.log("Add to List: no name configured, nothing stored", LogLevel.WARN)
            return NodeOutput(emptyMap())
        }
        val item = input.item(LIST_ADD_VALUE_IN)
        if (item == null) {
            context.log("Add to List: nothing wired in, $name unchanged", LogLevel.WARN)
            return NodeOutput(emptyMap())
        }
        val appended = readList(context, name) + anyToJsonElement(item.value, item.schema)
        context.variables.set(name, JsonArray(appended).toString())
        context.log("Add to List: $name now holds ${appended.size} item(s)")
        return NodeOutput(emptyMap())
    }
}
