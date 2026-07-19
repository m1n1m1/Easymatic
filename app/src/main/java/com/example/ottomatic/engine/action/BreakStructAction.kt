@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.flatViewFor
import com.example.ottomatic.domain.model.schema.jsonElementToValue
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Action for `action.break`. Reads a typed struct on its `struct` DATA input
 * port and emits one DATA output per field, each carrying the field's value
 * wrapped in an [Item] with the field's schema.
 *
 * This is the single adaptive break node — its field output ports are
 * resolved at design time from the connected struct's schema by
 * [com.example.ottomatic.domain.registry.effectivePorts]. At runtime this
 * action splits whatever [Item] arrives by reading its [ItemSchema.Object]
 * fields and extracting each field's typed value via kotlinx serialization.
 *
 * Field values are decoded to their proper Kotlin types (String/Long/Int/
 * Boolean/Double/Float/Map<String,String>/List/...) so downstream nodes and
 * EXPR interpolation see correctly-typed data, not stringified forms.
 *
 * If no item arrives on `struct` (no edge, or source produced nothing), the
 * action pulses `out` without producing any field items.
 */
class BreakStructAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val item = input.dataIn[STRUCT_PORT] ?: return ActionResult.passthrough("out")
        val schema = item.schema as? ItemSchema.Object
        val fields = if (schema != null && schema.kClass != null) {
            extractFields(item.value, schema.kClass!!, schema)
        } else {
            emptyMap()
        }
        return ActionResult(execOut = listOf("out"), dataOut = fields)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFields(
        value: Any?,
        kClass: KClass<out Any>,
        schema: ItemSchema.Object,
    ): Map<String, Item> {
        val serializer = serializer(kClass.java)
        val element = Json.encodeToJsonElement(serializer, value as Any)
        val jsonObj = (element as? JsonObject) ?: return emptyMap()
        val result = LinkedHashMap<String, Item>(schema.fields.size)
        for ((fieldName, fieldSchema) in schema.fields) {
            val child = jsonObj[fieldName] ?: continue
            val fieldValue = jsonElementToValue(child, fieldSchema)
            result[fieldName] = Item(
                value = fieldValue,
                schema = fieldSchema,
                flat = flatViewFor(fieldValue, fieldSchema),
            )
        }
        return result
    }

    companion object {
        const val TYPE_ID = "action.break"
        const val STRUCT_PORT = "struct"
    }
}
