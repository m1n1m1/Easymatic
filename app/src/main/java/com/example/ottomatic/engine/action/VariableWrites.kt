package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.VariableWrite
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.engine.BoundVariables
import com.example.ottomatic.engine.ExecutionContext

/**
 * What the three variable-writing actions share.
 *
 * All of them face the same two problems. A reference is an **id**, so anything
 * they say about a variable has to resolve its declaration first or the console
 * fills up with UUIDs. And all three can be refused for the same two reasons, which
 * ought to read identically wherever the user meets them.
 */

/** The declaration [ref] names, or null outside a workflow-bound context. */
internal fun ExecutionContext.variableDeclaration(ref: String): VariableDeclaration? =
    (variables as? BoundVariables)?.declarationOf(ref)

/** How a variable should be referred to on screen: its name, or the raw ref if it is gone. */
internal fun ExecutionContext.variableName(ref: String): String =
    variableDeclaration(ref)?.name ?: ref

/**
 * True when [ref] names a constant, checked *before* an action does any work.
 *
 * `action.list_add` needs this: refusing only at the write would have it read the
 * list, append to it and throw the result away.
 */
internal fun ExecutionContext.isConstantVariable(ref: String): Boolean =
    variableDeclaration(ref)?.constant == true

/**
 * Logs a refused write and answers whether it was refused, leaving the caller to
 * announce its own success — the three actions have three different things worth
 * saying when the write lands, and only the refusals are common.
 */
internal fun ExecutionContext.reportRefusal(node: String, ref: String, result: VariableWrite): Boolean = when (result) {
    VariableWrite.STORED -> false
    VariableWrite.REFUSED_CONSTANT -> {
        log("$node: '${variableName(ref)}' is a constant and cannot be changed", LogLevel.WARN)
        true
    }
    VariableWrite.REFUSED_UNDECLARED -> {
        log("$node: no variable chosen, nothing stored", LogLevel.WARN)
        true
    }
}
