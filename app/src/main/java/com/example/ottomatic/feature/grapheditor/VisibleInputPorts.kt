package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.effectiveInputPorts

/** Input handles to render in the editor. */
fun visibleInputPorts(
    definition: NodeTypeDefinition,
    workflow: Workflow,
    node: WorkflowNode,
): List<Port> = effectiveInputPorts(definition, workflow, node).filter { port ->
    port.kind != PortKind.DATA ||
        port.name in node.visibleDataInputs
}
