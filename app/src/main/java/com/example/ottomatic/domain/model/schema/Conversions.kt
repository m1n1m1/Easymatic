package com.example.ottomatic.domain.model.schema

import com.example.ottomatic.domain.model.config.ValueType

/**
 * The conversion table: the single definition of "can a value of one shape become
 * a value of another, and what setting does that".
 *
 * This is deliberately *not* part of [ItemSchema.isAssignableFrom]. The graph
 * stays strictly typed — an `Int` output is never silently accepted by a `Text`
 * input. Instead, when the editor sees a drop that the type system refuses, it
 * asks this table whether a conversion exists and, if so, places a
 * `transform.convert` node into the wire pre-set to the returned [ValueType]
 * (see `GraphEditorViewModel.commitConnection`). The conversion is therefore
 * always a real, visible, editable node, exactly as in Unreal Blueprints —
 * nothing is coerced behind the user's back.
 *
 * Every primitive pair converts, including the ones that can fail (`"abc"` to a
 * number). That is safe here precisely *because* the conversion is visible: the
 * inserted node carries an "If it fails" field the user can see and set.
 *
 * | from \ to        | Text | Number | Whole number | Yes/No | Date & time |
 * |------------------|------|--------|--------------|--------|-------------|
 * | Text             |  –   |   ✓    |      ✓       |   ✓    | ✓ parsed    |
 * | Number / Whole   |  ✓   |   ✓    |   ✓ truncate |  ✓ ≠0  | ✓ epoch     |
 * | Yes/No           |  ✓   |  ✓ 1/0 |    ✓ 1/0     |   –    |   ✗         |
 * | Date & time      | ✓ ISO| ✓ epoch|   ✓ epoch    |  ✓ ≠0  |   –         |
 * | Struct/List/Map  |  ✓ JSON |  ✗  |      ✗       |   ✗    |   ✗         |
 *
 * The two ✗ rows are not special-cased: a yes/no has no reading as a moment and a
 * struct has no reading as anything but text, so both simply fail to parse and land
 * on the node's own fallback.
 */
fun conversionTarget(source: ItemSchema?, target: ItemSchema?): ValueType? {
    // Only a primitive can be *produced*: there is no text-to-struct direction.
    val wanted = ValueType.of(target) ?: return null
    return when {
        // Anything at all can be rendered as text.
        wanted == ValueType.TEXT -> wanted
        // Everything else needs a source that is itself a scalar.
        isScalar(source) -> wanted
        else -> null
    }
}

/**
 * True when a value described by [schema] is a single scalar, and so has a
 * meaningful numeric or yes/no reading.
 *
 * A [ItemSchema.Union] counts: it is how a nullable property is described, and the
 * conversion is total anyway — a null simply lands on the fallback.
 */
private fun isScalar(schema: ItemSchema?): Boolean = when (schema) {
    is ItemSchema.Primitive -> true
    is ItemSchema.Union -> schema.alternatives.any { it is ItemSchema.Primitive }
    else -> false
}
