package com.example.ottomatic.domain.model

/**
 * The high-level category of a node.
 */
enum class NodeKind {
    TRIGGER,
    ACTION,
}

/**
 * Static description of a node type: what it is called, what it does and
 * which input/output ports it exposes.
 *
 * [iconKey] is a platform-agnostic identifier that the UI layer maps to an icon.
 */
data class NodeTypeDefinition(
    val typeId: String,
    val displayName: String,
    val description: String,
    val kind: NodeKind,
    val inputPorts: List<String>,
    val outputPorts: List<String>,
    val iconKey: String,
)
