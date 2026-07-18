package com.example.ottomatic.domain.model.schema

import kotlin.reflect.KClass

/**
 * Type-safe description of the data flowing on a [Port] of kind [PortKind.DATA].
 *
 * Schemas are *runtime* objects: they are never persisted as part of a
 * [com.example.ottomatic.domain.model.Workflow]. The persisted graph refers to
 * nodes by `typeId` and ports by name; the schema for a port is reconstructed
 * from the [com.example.ottomatic.domain.registry.NodeTypeRegistry] /
 * [com.example.ottomatic.domain.registry.ActionRegistry] at load time.
 *
 * Schemas are built from kotlinx-serialization descriptors so any
 * `@Serializable` Kotlin data class is automatically a valid item type.
 *
 * Subtyping is *structural* (TypeScript-style):
 *  - [Wildcard] is compatible with everything, in both directions. It is the
 *    documented escape hatch for nodes whose output shape is not statically
 *    known (detekt warns on its use).
 *  - Two [Object] schemas are compatible iff the target's fields all exist on
 *    the source with compatible schemas (the source may carry extra fields).
 *  - [ListSchema], [MapSchema], [Union] compose element-wise.
 *
 * Subtype names deliberately avoid colliding with `kotlin.Any`, `kotlin.Unit`,
 * `kotlin.collections.List` and `kotlin.collections.Map`.
 */
sealed interface ItemSchema {

    /** Wildcard schema. Compatible with every other schema in both directions. */
    data object Wildcard : ItemSchema

    /** Unit value — used on EXECUTION ports and triggers that carry no data. */
    data object Unit : ItemSchema

    /** A primitive scalar. [kClass] is one of String, Int, Long, Double, Float, Boolean. */
    data class Primitive(val kClass: KClass<out Any>) : ItemSchema

    /** A typed object whose fields are described by [fields]. */
    data class Object(val fields: Map<String, ItemSchema>, val kClass: KClass<out Any>? = null) : ItemSchema

    /** A homogeneous list of [element]s. */
    data class ListSchema(val element: ItemSchema) : ItemSchema

    /** A map with [key] keys and [value] values. */
    data class MapSchema(val key: ItemSchema, val value: ItemSchema) : ItemSchema

    /** A union of [alternatives]; a value matches any of them. */
    data class Union(val alternatives: kotlin.collections.List<ItemSchema>) : ItemSchema

    /**
     * True when a value described by [source] can flow into a port expecting
     * `this` schema. Read as: "this schema can be assigned FROM source".
     *
     * Direction: `target.isAssignableFrom(source)` — i.e. the receiver is the
     * input port schema and [source] is the connected output port schema.
     */
    @Suppress("CyclomaticComplexMethod")
    fun isAssignableFrom(source: ItemSchema): Boolean {
        if (source is Wildcard) return true
        return when (this) {
            Wildcard -> true
            Unit -> source is Unit
            is Primitive -> source is Primitive && source.kClass == kClass
            is Object -> when (source) {
                is Object -> fields.all { (name, expected) ->
                    val actual = source.fields[name] ?: return@all false
                    expected.isAssignableFrom(actual)
                }
                else -> false
            }
            is ListSchema -> source is ListSchema && element.isAssignableFrom(source.element)
            is MapSchema -> source is MapSchema &&
                key.isAssignableFrom(source.key) &&
                value.isAssignableFrom(source.value)
            is Union -> alternatives.any { it.isAssignableFrom(source) } || source is Union &&
                source.alternatives.any { alt -> alternatives.any { mine -> mine.isAssignableFrom(alt) } }
        }
    }
}

/** Convenience for building an [ItemSchema.Object] without spelling out the map. */
fun objectSchema(vararg pairs: Pair<String, ItemSchema>, kClass: KClass<out Any>? = null): ItemSchema.Object =
    ItemSchema.Object(fields = linkedMapOf(*pairs), kClass = kClass)
