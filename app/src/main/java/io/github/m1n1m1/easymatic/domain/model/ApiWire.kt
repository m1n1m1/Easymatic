package io.github.m1n1m1.easymatic.domain.model

import kotlinx.serialization.Serializable

/**
 * What [ApiContract.METHOD_LIST] answers: every `trigger.api` on the device, with
 * enough about each for a calling app to build a picker and then call it.
 *
 * **Deliberately not built on `SchemaWire`.** The plugin wire describes the host's
 * full type lattice because a plugin declares nodes *into* the graph and has to
 * agree with it exactly. A caller here is doing something much smaller — filling in
 * a handful of named values — and the vocabulary it should meet is
 * [PortSpec]'s own, which is five type names and a list flag. Handing it
 * `Union`/`MapOf`/`Wildcard` would describe possibilities this door cannot accept
 * anyway, and would tie a third-party JSON parser to a lattice that exists for
 * reasons it will never encounter.
 *
 * The whole payload is JSON in a Bundle string rather than a `Parcelable`, for the
 * reason the plugin wire is: a `Parcelable` across an app boundary is a class both
 * sides must compile, which would make reading this list require depending on us.
 * JSON makes the answer readable by anything, including a shell pipeline.
 */
@Serializable
data class ApiTriggerListWire(
    val version: Int = ApiContract.VERSION,
    val triggers: List<ApiTriggerWire> = emptyList(),
)

/** One callable trigger. [macroId] plus [nodeId] is what [ApiContract.METHOD_RUN] takes. */
@Serializable
data class ApiTriggerWire(
    val macroId: String,
    val macroName: String,
    val nodeId: String,
    /** What the trigger calls itself, for a caller's picker. Falls back to the node's then the macro's name. */
    val label: String,
    /**
     * Whether the macro's switch is on.
     *
     * A disabled macro is **listed** rather than omitted, and carries `false`, so a
     * caller's picker can grey it out and say why. Omitting it would hide a macro
     * the user can see in Easymatic and has to be told about — the alternative is a
     * picker that is silently missing the entry somebody is looking for, followed by
     * a [ApiContract.STATUS_DISABLED] they could have been warned about.
     */
    val enabled: Boolean,
    /**
     * The trigger's key, or blank when it has none.
     *
     * Handing the key to an approved caller is what makes the whole `list` method
     * worth having: the alternative is the user reading 32 characters off one screen
     * and typing them into another. It discloses nothing the caller cannot already
     * do — an approved caller may run any of these triggers without a key at all —
     * so the token here is a convenience for *forwarding*, e.g. an app that writes a
     * shell script or a webhook the user takes elsewhere.
     */
    val token: String,
    val inputs: List<ApiInputWire> = emptyList(),
)

/** One value the caller may pass, sent as an `in.<name>` extra. */
@Serializable
data class ApiInputWire(
    val name: String,
    /** `TEXT`, `NUMBER`, `WHOLE_NUMBER`, `YES_OR_NO`, `DATE_TIME`, or `ANY` for untyped. */
    val type: String = PortSpec.ANY,
    /** When true the value is read as a JSON array. */
    val list: Boolean = false,
)
