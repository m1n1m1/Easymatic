package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.core.model.NodeTypeId

/**
 * Central registry for all available node types (triggers, actions, values,
 * transforms).
 *
 * This object holds no declarations of its own: every node is declared exactly
 * once inside its own implementation file (an
 * [io.github.m1n1m1.easymatic.engine.ActionNodeDefinition],
 * [io.github.m1n1m1.easymatic.engine.TriggerNodeDefinition] or
 * [io.github.m1n1m1.easymatic.engine.ValueNodeDefinition]) and registered in
 * [ActionRegistry] / [TriggerRegistry] / [ValueRegistry]. The
 * [NodeTypeDefinition]s served here are derived views of those declarations, so
 * the editor, engine and persistence layers share a single source of truth.
 *
 * Port schemas are derived from the typed data classes in
 * `domain/model/items/` via
 * [io.github.m1n1m1.easymatic.domain.model.schema.schemaOf]. EXECUTION ports carry
 * no schema (it is never consulted).
 */
object NodeTypeRegistry {

    /** The compiled half: every node in the four registries, in their declared order. */
    private val builtIn: List<NodeTypeDefinition> =
        TriggerRegistry.all().map { it.definition.nodeType } +
            ActionRegistry.all().map { it.definition.nodeType } +
            ValueRegistry.all().map { it.definition.nodeType } +
            TransformRegistry.all().map { it.definition.nodeType }

    private val builtInById: Map<NodeTypeId, NodeTypeDefinition> = builtIn.associateBy { it.typeId }

    /**
     * Every node type available right now.
     *
     * This stopped being an immutable `val` when plugins arrived, and the change is
     * worth stating rather than absorbing. It is still a *derived view that nothing
     * adds to directly* — that rule is intact — but it is now derived from **two**
     * sources: the four compiled registries, and whichever plugins are installed and
     * enabled at this moment. Plugins concatenate last, which is also where they sit
     * in the palette.
     *
     * The cost is that callers reading this during composition see a list that can
     * change. `NodePaletteOverlay` and `GraphEditorViewModel` therefore key on
     * [PluginNodes.entries] so that Compose knows to look again; anything reading it
     * outside composition is unaffected, because a plugin set changes only on install,
     * uninstall, or the user flipping a switch.
     */
    val all: List<NodeTypeDefinition>
        get() = builtIn + PluginNodes.all().map { it.definition }

    fun byId(typeId: NodeTypeId): NodeTypeDefinition? =
        builtInById[typeId] ?: PluginNodes.byId(typeId)?.definition

    fun byKindAndCategory(kind: NodeKind, category: NodeCategory): List<NodeTypeDefinition> =
        all.filter { it.kind == kind && it.category == category }

    fun categoriesFor(kind: NodeKind): List<NodeCategory> =
        NodeCategory.values().filter { it.kind == kind && byKindAndCategory(kind, it).isNotEmpty() }

    /**
     * How the palette groups a kind's nodes: the app's own categories first, then one
     * group per plugin contributing to this kind.
     *
     * A group per plugin rather than folding them into the categories above, and the
     * reason is not only that [NodeCategory] is closed: somebody about to remove a
     * plugin needs to see which nodes will go with it, and a heading reading "Acme
     * Tools" says that where "Data" does not.
     */
    fun groupsFor(kind: NodeKind): List<PaletteGroup> {
        val builtInGroups = categoriesFor(kind).map { PaletteGroup.Builtin(it) }
        val pluginGroups = PluginNodes.all()
            .filter { it.definition.kind == kind }
            .distinctBy { it.packageName }
            .map { PaletteGroup.Plugin(it.packageName, it.pluginName, kind) }
        return builtInGroups + pluginGroups
    }

    /** The nodes in one palette group, in registry order. */
    fun nodesIn(group: PaletteGroup): List<NodeTypeDefinition> = when (group) {
        is PaletteGroup.Builtin -> byKindAndCategory(group.kind, group.category)
        is PaletteGroup.Plugin -> PluginNodes.all()
            .filter { it.packageName == group.packageName && it.definition.kind == group.kind }
            .map { it.definition }
    }
}

/**
 * A heading in the node palette.
 *
 * Introduced because a plugin's nodes cannot honestly be filed under one of the app's
 * own 27 categories, and opening that enum would have cost the exhaustive `when` that
 * keeps all 27 of them meaningful in order to serve a group that is synthesised
 * anyway. The four `PLUGIN_*` entries on [NodeCategory] exist only so a plugin node's
 * `category` stays non-null; they are never drawn.
 */
sealed interface PaletteGroup {

    val kind: NodeKind
    val displayName: String

    data class Builtin(val category: NodeCategory) : PaletteGroup {
        override val kind: NodeKind get() = category.kind
        override val displayName: String get() = category.displayName
    }

    data class Plugin(
        val packageName: String,
        val pluginName: String,
        override val kind: NodeKind,
    ) : PaletteGroup {
        override val displayName: String get() = pluginName
    }
}
