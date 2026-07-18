package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode

/**
 * A unit of executable behaviour behind a node whose [NodeTypeDefinition]
 * has [NodeKind.ACTION].
 *
 * Implementations live in `engine/action/` and are registered in
 * [com.example.ottomatic.domain.registry.ActionRegistry].
 */
interface Action {

    val typeId: String

    suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult
}

/**
 * Resolves `{{field}}` placeholders in [template] against [payload] values.
 * Used by notify/http actions so users can reference upstream data.
 */
fun interpolate(template: String, payload: WorkflowPayload): String {
    var result = template
    payload.values.forEach { (key, value) ->
        result = result.replace("{{$key}}", value)
    }
    return result
}
