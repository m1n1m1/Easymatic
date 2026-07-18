package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode

/**
 * The payload carried through the workflow when a trigger fires, and passed
 * as input to downstream actions. Each action may enrich it before forwarding.
 */
data class WorkflowPayload(
    val values: Map<String, String> = emptyMap(),
) {
    operator fun plus(extra: Map<String, String>): WorkflowPayload =
        WorkflowPayload(values + extra)
}

/**
 * Input handed to an [Action] when it runs: the node being executed and the
 * payload accumulated so far from the trigger and preceding actions.
 */
data class ActionInput(
    val node: WorkflowNode,
    val payload: WorkflowPayload,
)

/**
 * Result of running an action. [outputs] maps output-port index to the
 * payload that should flow out of that port. Ports not present in the map
 * produce nothing downstream (used by the condition node's true/false branch).
 */
data class ActionResult(
    val outputs: Map<Int, WorkflowPayload> = emptyMap(),
) {
    companion object {
        /** Forwards the input unchanged out of port 0 (the common "out" port). */
        fun passthrough(input: ActionInput): ActionResult =
            ActionResult(mapOf(0 to input.payload))
    }
}
