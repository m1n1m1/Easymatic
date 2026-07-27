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
        wildcardInputs = listOf(structDataIn(BREAK_STRUCT_IN.value, label = "Struct")),
    )

    override suspend fun executeRaw(
        config: NoConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        val item = input.item(BREAK_STRUCT_IN)
        val schema = item?.schema as? ItemSchema.Object
        val kClass = schema?.kClass
        return if (schema == null || kClass == null) {
            NodeOutput(emptyMap())
        } else {
            NodeOutput(extractFields(item.value, kClass, schema))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFields(
        value: Any?,
        kClass: KClass<out Any>,
        schema: ItemSchema.Object,
    ): Map<PortName, Item> {
        val serializer = serializer(kClass.java)
        val element = Json.encodeToJsonElement(serializer, value as Any)
        val jsonObj = (element as? JsonObject) ?: return emptyMap()
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
