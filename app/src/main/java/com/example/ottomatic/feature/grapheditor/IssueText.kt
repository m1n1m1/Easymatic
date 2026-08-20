package com.example.ottomatic.feature.grapheditor

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import com.example.ottomatic.engine.validation.IssueReason
import com.example.ottomatic.engine.validation.ValidationIssue

/**
 * How the Problems panel words a finding.
 *
 * The exhaustive `when` is the point: `IssueReason` is a closed set, so the compiler
 * refuses a new member until somebody has written its sentence, and a sentence with
 * no member is dead code it will find. The validator itself stays in `engine/`,
 * which may not reach resources — it names the reason and hands over the values that
 * fill the blanks.
 *
 * A null reason falls back to the issue's own English. Nothing produces one today;
 * it exists so a finding added in a hurry still says something rather than nothing.
 */
@Composable
// `stringResource` takes a vararg and the argument count varies per reason, so the
// spread is the only way to call it. At most three short strings, once per row.
@Suppress("SpreadOperator")
internal fun ValidationIssue.text(): String {
    val reason = reason ?: return message
    return stringResource(reason.messageRes(), *args.toTypedArray())
}

@StringRes
@Suppress("CyclomaticComplexMethod") // A flat copy table, not branching logic.
private fun IssueReason.messageRes(): Int = when (this) {
    IssueReason.PERMISSION_MISSING -> R.string.problem_permission_missing
    IssueReason.CAPABILITY_MISSING -> R.string.problem_capability_missing
    IssueReason.PLUGIN_PERMISSION_MISSING -> R.string.problem_plugin_permission_missing
    IssueReason.PLUGIN_NOT_READY -> R.string.problem_plugin_not_ready
    IssueReason.MACRO_UNSET -> R.string.problem_macro_unset
    IssueReason.MACRO_MISSING -> R.string.problem_macro_missing
    IssueReason.HUB_UNSET -> R.string.problem_hub_unset
    IssueReason.HUB_MISSING -> R.string.problem_hub_missing
    IssueReason.AI_UNSET -> R.string.problem_ai_unset
    IssueReason.AI_MISSING -> R.string.problem_ai_missing
    IssueReason.AI_UNFINISHED -> R.string.problem_ai_unfinished
    IssueReason.VARIABLE_UNSET -> R.string.problem_variable_unset
    IssueReason.VARIABLE_MISSING -> R.string.problem_variable_missing
    IssueReason.EMPTY_LOOP_BODY -> R.string.problem_empty_loop_body
    IssueReason.UNKNOWN_NODE_TYPE -> R.string.problem_unknown_node_type
    IssueReason.NO_TRIGGER -> R.string.problem_no_trigger
    IssueReason.TRIGGER_NOT_WIRED -> R.string.problem_trigger_not_wired
    IssueReason.EXEC_WIRE_DANGLING -> R.string.problem_exec_wire_dangling
    IssueReason.EXEC_OUTPUT_UNKNOWN -> R.string.problem_exec_output_unknown
    IssueReason.EXEC_INPUT_UNKNOWN -> R.string.problem_exec_input_unknown
    IssueReason.DATA_WIRE_DANGLING -> R.string.problem_data_wire_dangling
    IssueReason.DATA_WIRE_UNKNOWN_TYPE -> R.string.problem_data_wire_unknown_type
    IssueReason.NOT_DATA_OUTPUT -> R.string.problem_not_data_output
    IssueReason.NOT_DATA_INPUT -> R.string.problem_not_data_input
    IssueReason.SCHEMA_MISMATCH -> R.string.problem_schema_mismatch
    IssueReason.VALUE_NOT_CONNECTED -> R.string.problem_value_not_connected
    IssueReason.NOTHING_WIRED_IN -> R.string.problem_nothing_wired_in
    IssueReason.EXEC_CYCLE -> R.string.problem_exec_cycle
    IssueReason.DATA_CYCLE -> R.string.problem_data_cycle
    IssueReason.NOT_EXEC_UPSTREAM -> R.string.problem_not_exec_upstream
    IssueReason.FORK_BRANCH_CROSS -> R.string.problem_fork_branch_cross
}
