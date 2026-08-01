package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.registry.ConfigSchemaRegistry
import com.example.ottomatic.domain.registry.effectiveInputPorts

/**
 * Input handles to render in the editor.
 *
 * A DATA input derived from a `@Wired` config property is hidden until the user
 * opts in with the socket toggle beside its form field: the value is usually
 * typed rather than wired, and showing every one of them would give an ordinary
 * action a row of handles nobody uses.
 *
 * A DATA input with **no config field behind it** is always shown, because there
 * is nothing to opt in *from* — a wildcard input (`action.script`'s A/B/C,
 * `action.break`'s struct, `transform.convert`'s value) has no form row and so
 * no toggle, and hiding it would leave a node that cannot be wired at all.
 */
fun visibleInputPorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> {
    val typedInForm: Set<PortName> = ConfigSchemaRegistry.byId(definition.typeId)
        ?.fields
        ?.mapTo(mutableSetOf()) { PortName(it.key.value) }
        .orEmpty()
    return effectiveInputPorts(definition, workflow, node).filter { port ->
        port.kind != PortKind.DATA ||
            port.name in node.visibleDataInputs ||
            port.name !in typedInForm
    }
}
