package com.example.ottomatic.engine.validation

/**
 * What a [ValidationIssue] is *about*, separate from how it is worded.
 *
 * The Problems panel is user-facing, so its sentences have to come out of string
 * resources — and `engine/` may not reach `feature/` or the resources it resolves.
 * So the validator names the reason and supplies the values that go in the blanks,
 * and `ProblemsPanel` turns the pair into a sentence with an exhaustive `when`.
 *
 * A **typed reason rather than a string key**, because that `when` is then checked
 * in both directions: a new member does not compile until somebody has written the
 * sentence for it, and a sentence with no member is dead code the compiler finds.
 * That is the same reasoning as `CategoryLabels` and `PermissionCopy` — the answer
 * set is closed, so the direct route is available and is the better one.
 *
 * [ValidationIssue.message] keeps the English text regardless, because it is also
 * what `executeFrom` writes to the run log, which is not translated.
 *
 * The comment beside each member names the arguments it expects, in order.
 */
enum class IssueReason {
    /** node, needs */
    PERMISSION_MISSING,

    /** node, needs, plugin */
    PLUGIN_PERMISSION_MISSING,

    /**
     * node, plugin, why
     *
     * `why` is the plugin's own sentence and is **not translated** — see
     * `GraphValidator.validatePluginReadiness`.
     */
    PLUGIN_NOT_READY,

    /** node */
    MACRO_UNSET,

    /** node */
    MACRO_MISSING,

    /** node */
    HUB_UNSET,

    /** node */
    HUB_MISSING,

    /** node */
    AI_UNSET,

    /** node */
    AI_MISSING,

    /** node */
    AI_UNFINISHED,

    // AI_TOOL_MISSING and AI_TOOL_UNPINNED were here. Tools moved from the node onto
    // the model profile, so a broken one is a fact about the connection library and is
    // reported on the row that fixes it — a Problems entry would point at the canvas,
    // where there is nothing to change.

    /** node */
    VARIABLE_UNSET,

    /** node */
    VARIABLE_MISSING,

    /** node */
    EMPTY_LOOP_BODY,

    /** node, typeId */
    UNKNOWN_NODE_TYPE,

    /** none */
    NO_TRIGGER,

    /** trigger */
    TRIGGER_NOT_WIRED,

    /** none */
    EXEC_WIRE_DANGLING,

    /** port, node */
    EXEC_OUTPUT_UNKNOWN,

    /** port, node */
    EXEC_INPUT_UNKNOWN,

    /** none */
    DATA_WIRE_DANGLING,

    /** none */
    DATA_WIRE_UNKNOWN_TYPE,

    /** port, node */
    NOT_DATA_OUTPUT,

    /** port, node */
    NOT_DATA_INPUT,

    /** source schema, target schema */
    SCHEMA_MISMATCH,

    /** node */
    VALUE_NOT_CONNECTED,

    /** node */
    NOTHING_WIRED_IN,

    /** cycle path */
    EXEC_CYCLE,

    /** cycle path */
    DATA_CYCLE,

    /** producer, consumer */
    NOT_EXEC_UPSTREAM,

    /** from, to, fork */
    FORK_BRANCH_CROSS,
}
