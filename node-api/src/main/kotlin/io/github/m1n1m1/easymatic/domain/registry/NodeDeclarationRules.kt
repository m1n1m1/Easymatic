package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.PortKind

/**
 * The rules that make a node declaration coherent, as a function rather than as a
 * test.
 *
 * These were written once, as assertions inside `NodeDeclarationContractTest`, and
 * that was the right place while every node in existence was compiled into the app.
 * A third-party declaration arrives at *runtime*, where no test can reach it — and
 * the rules it has to satisfy are not merely similar to the first-party ones, they
 * are the same rules for the same reasons. A value node with a data input breaks
 * the pull side's contract whoever wrote it; a transform with two data outputs
 * breaks the executor's memo, which is keyed by node and not by port.
 *
 * So there is one rule set with three callers: the plugin loader
 * ([io.github.m1n1m1.easymatic.nodeapi.plugin.PluginDeclarationValidator]), a plugin
 * author's own JUnit test, and `NodeDeclarationContractTest` over the app's own
 * registries. That is the move `TimeOfDay`, `WebUrl` and `MessengerLink` already
 * are, applied where it also happens to solve the untrusted-input problem.
 *
 * What is deliberately *not* here is any rule needing the graph or the app's own
 * registries. The adaptive-port checks resolve through `effectivePorts`, which
 * walks a `Workflow`; the "a wired config property becomes exactly one data input"
 * check needs `NodeSchema.wiredPorts`, which distinguishes a config-derived port
 * from a node's own and which a [NodeTypeDefinition] has flattened away. Both stay
 * in the test, where the extra context exists.
 */
object NodeDeclarationRules {

    /**
     * Everything wrong with this declaration, one sentence each, or an empty list.
     *
     * Each sentence completes "Node <typeId> …", so the caller supplies the subject
     * and this supplies the predicate.
     *
     * [config] is null for a node with no configurable fields, which is legal.
     */
    fun problems(definition: NodeTypeDefinition, config: NodeConfigSchema?): List<String> = buildList {
        addAll(portProblems(definition))
        addAll(configProblems(config))
        addAll(kindProblems(definition))
    }

    private fun portProblems(definition: NodeTypeDefinition): List<String> = buildList {
        for (direction in Direction.entries) {
            val duplicates = definition.ports
                .filter { it.direction == direction }
                .map { it.name.value }
                .duplicates()
            if (duplicates.isNotEmpty()) {
                add("has more than one $direction port named ${duplicates.joinToString()}")
            }
        }
        for (port in definition.ports) {
            when (port.kind) {
                // A DATA port with no schema type-checks against nothing, so every
                // edge into it is accepted and nothing downstream ever narrows.
                PortKind.DATA -> if (port.schema == null) {
                    add("declares a data port '${port.name.value}' with no schema")
                }
                // An EXECUTION port's schema is never read; one that carries a schema
                // is a declaration whose author expected data to flow down a pulse.
                PortKind.EXECUTION -> if (port.schema != null) {
                    add("declares an execution port '${port.name.value}' carrying a schema")
                }
            }
            if (port.label.isBlank()) add("declares a port '${port.name.value}' with a blank label")
        }
    }

    private fun configProblems(config: NodeConfigSchema?): List<String> = buildList {
        val fields = config?.fields ?: return@buildList
        val duplicates = fields.map { it.key.value }.duplicates()
        if (duplicates.isNotEmpty()) add("has more than one config field keyed ${duplicates.joinToString()}")
        for (field in fields) {
            if (field.key.value.isBlank()) add("declares a config field with a blank key")
            if (field.label.isBlank()) add("declares a config field '${field.key.value}' with a blank label")
            val type = field.type
            if (type !is ConfigFieldType.ENUM) continue
            if (type.options.isEmpty()) {
                add("declares an empty choice field '${field.key.value}'")
            } else if (field.defaultValue !in type.options.map { it.value }) {
                // Unchecked, this shows as a form opening on a blank selection that
                // matches no branch the node tests for — the node then takes whichever
                // branch its `when` falls through to, for every untouched instance.
                add(
                    "declares a choice field '${field.key.value}' defaulting to " +
                        "'${field.defaultValue}', which is not one of its options",
                )
            }
        }
        addAll(visibilityProblems(fields))
    }

    private fun visibilityProblems(fields: List<ConfigField<*>>): List<String> {
        val byKey = fields.associateBy { it.key }
        val perField = fields.mapNotNull { visibilityProblem(it, byKey) }
        val cycle = "has config fields whose visibility rules form a cycle"
            .takeIf { fields.any { field -> cyclesFrom(field, byKey) } }
        return perField + listOfNotNull(cycle)
    }

    private fun visibilityProblem(field: ConfigField<*>, byKey: Map<ConfigKey, ConfigField<*>>): String? {
        val rule = field.visibleWhen ?: return null
        val shown = "field '${field.key.value}' is shown when '${rule.key.value}'"
        val controller = byKey[rule.key]
        val allowed = controller?.let { allowedValues(it.type) }.orEmpty()
        val unknown = rule.values - allowed
        return when {
            controller == null -> "$shown is set, but there is no such field"
            controller.key == field.key -> "field '${field.key.value}' is shown when it is itself set"
            allowed.isNotEmpty() && unknown.isNotEmpty() ->
                "$shown is ${unknown.joinToString()}, which it can never be"
            else -> null
        }
    }

    /**
     * True when following [field]'s visibility chain revisits a field it already
     * passed — which would make the form's answer to "is this row visible?" depend on
     * itself.
     */
    private fun cyclesFrom(field: ConfigField<*>, byKey: Map<ConfigKey, ConfigField<*>>): Boolean {
        val seen = mutableSetOf(field.key)
        return generateSequence(field) { current -> current.visibleWhen?.key?.let(byKey::get) }
            .drop(1)
            .any { !seen.add(it.key) }
    }

    /** The values a controlling field can actually hold, or empty when unbounded. */
    private fun allowedValues(type: ConfigFieldType<*>): Set<String> = when (type) {
        is ConfigFieldType.ENUM -> type.options.mapTo(mutableSetOf()) { it.value }
        ConfigFieldType.BOOL -> setOf("true", "false")
        else -> emptySet()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun kindProblems(definition: NodeTypeDefinition): List<String> = buildList {
        val execPorts = definition.ports.filter { it.kind == PortKind.EXECUTION }
        val dataIn = definition.inputs(PortKind.DATA)
        val dataOut = definition.outputs(PortKind.DATA)
        when (definition.kind) {
            NodeKind.TRIGGER -> {
                if (execPorts.size != 1 || execPorts.single().direction != Direction.OUT) {
                    add("is a trigger, so it must have exactly one execution port and it must be an output")
                }
                if (dataIn.isNotEmpty()) add("is a trigger, so it cannot have data inputs")
            }
            NodeKind.ACTION -> {
                if (execPorts.none { it.direction == Direction.IN }) {
                    add("is an action, so it must have an execution input")
                }
                if (execPorts.none { it.direction == Direction.OUT }) {
                    add("is an action, so it must have at least one execution output")
                }
            }
            // The pull side. A value is read outside the execution order, with no
            // pulse, at a moment its consumer decides — which is sound only while the
            // read is a cheap, repeatable leaf that answers null rather than throwing.
            NodeKind.VALUE -> {
                if (execPorts.isNotEmpty()) add("is a value, so it cannot have execution ports")
                if (dataIn.isNotEmpty()) add("is a value, so it cannot have data inputs")
                if (dataOut.size != 1) add("is a value, so it must have exactly one data output")
            }
            NodeKind.TRANSFORM -> {
                if (execPorts.isNotEmpty()) add("is a transform, so it cannot have execution ports")
                if (dataIn.isEmpty()) add("is a transform, so it must have at least one data input")
                // Load-bearing: the executor's pull memo is keyed by node, not by port.
                if (dataOut.size != 1) add("is a transform, so it must have exactly one data output")
                if (definition.permissionRequirements.isNotEmpty()) {
                    add("is a transform, so it cannot require a permission")
                }
            }
        }
    }

    private fun List<String>.duplicates(): List<String> =
        groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
}
