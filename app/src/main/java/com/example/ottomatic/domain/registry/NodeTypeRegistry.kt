package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.core.model.NodeTypeId

/**
 * Central registry for all available node types (triggers, actions, conditions).
 *
 * This object holds no declarations of its own: every node is declared exactly
 * once inside its own implementation file (an
 * [com.example.ottomatic.engine.ActionNodeDefinition],
 * [com.example.ottomatic.engine.TriggerNodeDefinition] or
 * [com.example.ottomatic.engine.ConditionNodeDefinition]) and registered in
 * [ActionRegistry] / [TriggerRegistry] / [ConditionRegistry]. The
 * [NodeTypeDefinition]s served here are derived views of those declarations, so
 * the editor, engine and persistence layers share a single source of truth.
 *
 * A condition contributes exactly one entry here — its canvas-placement view.
 * Its attached placement carries no node type, because it is not a node on the
 * graph; see [com.example.ottomatic.domain.model.AttachedCondition].
 *
 * Port schemas are derived from the typed data classes in
 * `domain/model/items/` via
 * [com.example.ottomatic.domain.model.schema.schemaOf]. EXECUTION ports carry
 * no schema (it is never consulted).
 */
object NodeTypeRegistry {

    val all: List<NodeTypeDefinition> =
        TriggerRegistry.all().map { it.definition.nodeType } +
            ActionRegistry.all().map { it.definition.nodeType } +
            ConditionRegistry.all().map { it.definition.nodeType }

    private val byId: Map<NodeTypeId, NodeTypeDefinition> = all.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): NodeTypeDefinition? = byId[typeId]

    fun byKind(kind: NodeKind): List<NodeTypeDefinition> = all.filter { it.kind == kind }

    fun byKindAndCategory(kind: NodeKind, category: NodeCategory): List<NodeTypeDefinition> =
        all.filter { it.kind == kind && it.category == category }

    fun categoriesFor(kind: NodeKind): List<NodeCategory> =
        NodeCategory.values().filter { it.kind == kind && byKindAndCategory(kind, it).isNotEmpty() }
}
