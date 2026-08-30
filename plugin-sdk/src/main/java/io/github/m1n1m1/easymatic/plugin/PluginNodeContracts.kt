package io.github.m1n1m1.easymatic.plugin

import io.github.m1n1m1.easymatic.domain.model.config.ChoiceChooser
import io.github.m1n1m1.easymatic.domain.registry.ConfigFieldType

/**
 * The rules a plugin node has to satisfy that its *declaration* cannot express.
 *
 * `PluginDeclarationValidator` reads a `NodeDeclarationWire` — a document, arriving over a
 * binder, from a process that no longer has the node object that produced it. That is the
 * right shape for everything about the declaration, and it is blind to everything about
 * the **class**: whether the node that declared a chooser can actually answer one is a
 * fact about which interfaces it implements, and the wire carries no interfaces.
 *
 * So this is the second half, on the plugin's own side, with two callers for the same
 * reason `NodeDeclarationRules` has three: [BaseEasymaticPluginService] runs it when it
 * builds its manifest, and a plugin author's own JUnit test runs it beside
 * `PluginDeclarationValidator.validate` — which `docs/PLUGINS.md` already tells them to
 * write, and which is where a problem costs seconds rather than a reinstall.
 *
 * Reported rather than thrown, and that is deliberate. A throw from the manifest reaches
 * Easymatic as a dead transaction, which it can only report as *"Could not be reached"* —
 * the least useful sentence available, and one that points at the wrong thing entirely.
 */
object PluginNodeContracts {

    /** Everything wrong with [node], each sentence completing "Plugin node <class> …". */
    fun problems(node: Any): List<String> {
        val definition = definitionOf(node)
            ?: return listOf(
                "is in `nodes` but implements none of PluginAction, PluginTrigger, PluginValue or " +
                    "PluginTransform, so nothing would ever call it",
            )
        return choiceProblems(node, definition)
    }

    /** Every problem across [nodes], each prefixed with the class it belongs to. */
    fun problems(nodes: List<Any>): List<String> =
        nodes.flatMap { node -> problems(node).map { "Plugin node ${node.javaClass.name} $it" } }

    /**
     * A node may not declare a chooser it cannot fill.
     *
     * The failure this catches is the quiet kind: the field renders, the chooser opens,
     * the plugin answers "not a choice source", and the list is empty forever — which
     * looks exactly like a workspace that genuinely has no pages in it.
     *
     * **Only [ChoiceChooser.LIST] fields are covered**, and the omission is not a gap. A
     * [ChoiceChooser.SCREEN] field is served by an Activity in the plugin's *manifest*,
     * which no amount of reflection over a node class can see; the host resolves that one
     * itself through `PackageManager`, at the moment it reads the plugin, and reports it
     * in the chooser where somebody is looking.
     */
    private fun choiceProblems(node: Any, definition: PluginNodeDefinition<*, *>): List<String> {
        val choosers = definition.schema.fields
            .mapNotNull { field ->
                (field.type as? ConfigFieldType.PLUGIN_CHOICE)
                    ?.takeIf { it.chooser == ChoiceChooser.LIST }
                    ?.let { field.key.value }
            }
        if (choosers.isEmpty() || node is PluginChoiceSource<*>) return emptyList()
        return listOf(
            "declares @PluginChoice on ${choosers.joinToString(", ") { "'$it'" }} but does not " +
                "implement PluginChoiceSource, so the chooser would open on an empty list",
        )
    }

    /**
     * This node's definition, whichever of the four contracts it arrived under.
     *
     * The same `when` [BaseEasymaticPluginService] uses to find a typeId, kept here rather
     * than shared with it because that one answers `""` for a non-node and this one has to
     * answer *nothing*, which is the whole first check above.
     */
    private fun definitionOf(node: Any): PluginNodeDefinition<*, *>? = when (node) {
        is PluginAction<*, *> -> node.definition
        is PluginTrigger<*, *> -> node.definition
        is PluginValue<*, *> -> node.definition
        is PluginTransform<*, *> -> node.definition
        else -> null
    }
}
