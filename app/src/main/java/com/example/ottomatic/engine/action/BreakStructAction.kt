@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.flatViewFor
import com.example.ottomatic.domain.model.schema.jsonElementToValue
import com.example.ottomatic.domain.model.structDataIn
import com.example.ottomatic.domain.registry.BREAK_STRUCT_IN
import com.example.ottomatic.domain.registry.BREAK_TYPE_ID
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.adaptiveNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Action for `action.break`. Reads a typed struct on its `struct` DATA input
 * port and emits one DATA output per field, each carrying the field's value
 * wrapped in an [Item] with the field's schema.
 *
 * The other adaptive node: its field output ports are resolved at design time
 * from the connected struct's schema by
 * [com.example.ottomatic.domain.registry.effectivePorts], so its output map is
 * keyed by port names that only exist at runtime — the one place a port-keyed
 * map is unavoidable, which is why it is a [RawAction].
 *
 * Field values are decoded to their proper Kotlin types (String/Long/Int/
 * Boolean/Double/Float/Map<String,String>/List/...) so downstream nodes see
 * correctly-typed data, not stringified forms.
 *
 * If no item arrives on `struct`, the action pulses `out` without producing any
 * field items.
 */
class BreakStructAction : RawAction<NoConfig> {

    override val definition = adaptiveNode<NoConfig>(
        typeId = BREAK_TYPE_ID.value,
        displayName = "Break Struct",
        description = "Splits a struct into its individual fields (auto-detects the struct from the input)",
        category = NodeCategory.DATA,
        icon = NodeIcon.SPLIT,
        extraPorts = listOf(structDataIn(BREAK_STRUCT_IN.value, label = "Struct")),
    )

    override suspend fun executeRaw(
        config: NoConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        val item = input.item(BREAK_STRUCT_IN)
        val schema = item?.schema as? ItemSchema.Object
        val json = schema?.let { jsonObjectOf(item, it) }
        return NodeOutput(if (json == null) emptyMap() else extractFields(json, schema))
    }

    /**
     * The struct's fields as JSON, by whichever of the two routes applies.
     *
     * Asking for [ItemSchema.Object.kClass] and giving up without one was the whole
     * implementation until 2026-08-10, and it was wrong in a way that looked right:
     * `effectivePorts` draws this node's output ports from `schema.fields`, which a
     * `kClass`-less struct has — so the card sprouted a full set of correctly-typed,
     * correctly-labelled ports and *nothing ever arrived on any of them*. That is
     * worse than the "sprouts no output ports at all" failure `structDataIn`'s
     * `ANY_STRUCT` was chosen to prevent, because there is nothing on screen to see.
     *
     * A struct whose value is already a [JsonObject] needs no serializer, and there
     * are now two sources of those: anything that came through `transform.json_read`,
     * and every struct a plugin node produces — a plugin's class does not exist in
     * this process, so its schema can never carry a `kClass`. The serializer route
     * stays for first-party structs, where the value is a real Kotlin data class.
     */
    private fun jsonObjectOf(item: Item, schema: ItemSchema.Object): JsonObject? =
        item.value as? JsonObject
            ?: schema.kClass?.let { kClass ->
                item.value?.let { value -> runCatching { encodeThrough(kClass, value) }.getOrNull() }
            }

    private fun encodeThrough(kClass: KClass<out Any>, value: Any): JsonObject? =
        Json.encodeToJsonElement(serializer(kClass.java), value) as? JsonObject

    private fun extractFields(
        jsonObj: JsonObject,
        schema: ItemSchema.Object,
    ): Map<PortName, Item> {
        val result = LinkedHashMap<PortName, Item>(schema.fields.size)
        for ((fieldName, fieldSchema) in schema.fields) {
            val child = jsonObj[fieldName] ?: continue
            val fieldValue = jsonElementToValue(child, fieldSchema)
            result[PortName(fieldName)] = Item(
                value = fieldValue,
                schema = fieldSchema,
                flat = flatViewFor(fieldValue, fieldSchema),
            )
        }
        return result
    }
}
