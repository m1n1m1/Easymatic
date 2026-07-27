package com.example.ottomatic.engine.transform

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.wildcardDataIn
import com.example.ottomatic.domain.registry.CONVERT_IN
import com.example.ottomatic.domain.registry.CONVERT_TYPE_ID
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.RawTransform
import com.example.ottomatic.engine.adaptiveTransformNode
import kotlinx.serialization.Serializable

/**
 * Config for `transform.convert`.
 *
 * [fallback] is the whole reason this node is visible rather than an invisible
 * coercion on the edge: a conversion that cannot succeed ("abc" to a number) has
 * to land somewhere, and the user gets to say where.
 */
@Serializable
data class ConvertConfig(
    @Label("Convert to") val to: ValueType = ValueType.TEXT,
    @Label("If it fails") val fallback: String = "",
)

/**
 * `transform.convert` — turns a value of one type into a value of another.
 *
 * Usually the user never picks this node from the palette: dragging a wire between
 * two convertible-but-mismatched ports makes the editor place one here already
 * configured (see `GraphEditorViewModel.commitConnection` and
 * [com.example.ottomatic.domain.model.schema.conversionTarget]), which is how
 * Unreal Blueprints solves the same problem.
 *
 * Its output port is declared [ItemSchema.Wildcard] and retyped at design time by
 * [com.example.ottomatic.domain.registry.effectivePorts] — to the `to` family's
 * primitive, or to the consumer's own primitive when that is a narrower member of
 * the same family (a `Long` counter, a `Float` accuracy). The item produced at
 * runtime carries the family default; nothing reads an item's schema to decide
 * behaviour, and every consumer parses through
 * [com.example.ottomatic.domain.model.schema.asText], so `Int` 5 and `Long` 5 are
 * indistinguishable downstream.
 */
class ConvertTransform : RawTransform<ConvertConfig> {

    override val definition = adaptiveTransformNode<ConvertConfig>(
        typeId = CONVERT_TYPE_ID.value,
        displayName = "Convert",
        description = "Turns a value into another type — text, a number, yes/no or a date",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.CONVERT,
        wildcardInputs = listOf(wildcardDataIn(CONVERT_IN.value, label = "Value")),
        output = Port(
            name = TRANSFORM_OUT,
            kind = PortKind.DATA,
            direction = Direction.OUT,
            schema = ItemSchema.Wildcard,
            label = "Value",
        ),
    )

    override suspend fun transformItem(
        config: ConvertConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Item = config.to.convert(item = input.item(CONVERT_IN), fallback = config.fallback)
}
