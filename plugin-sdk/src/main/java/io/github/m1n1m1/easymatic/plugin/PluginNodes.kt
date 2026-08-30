@file:Suppress("TooManyFunctions")

package io.github.m1n1m1.easymatic.plugin

import io.github.m1n1m1.easymatic.domain.model.DataOut
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.registry.nodeSchema
import io.github.m1n1m1.easymatic.nodeapi.wire.ExecOutputsWire
import io.github.m1n1m1.easymatic.nodeapi.wire.OptionWire
import io.github.m1n1m1.easymatic.nodeapi.wire.RouteWire

// The six shapes a plugin node may be, and the six builders that declare them.
//
// They deliberately mirror the app's own `actionNode` / `effectNode` / `triggerNode` /
// `pulseTriggerNode` / `valueNode` / `transformNode`, down to the parameter names, so
// that a plugin node file and a first-party one read the same. Five of the app's
// eleven builders have no counterpart here, each for its own reason:
//
//  - `loopNode` — a loop returning a thousand iteration maps in one binder call is a
//    TransactionTooLargeException, and a conditional loop is a round trip per pass.
//    Wire an `action.repeat` around a plugin node instead.
//  - `adaptiveNode` / `adaptiveTransformNode` / `adaptiveValueNode` — `effectivePorts`
//    resolves ports by walking the host's graph, on every keystroke in the editor,
//    from paths that can neither suspend nor be injected into. A plugin cannot be
//    handed the graph.
//  - `rawTransformNode` and the `Raw*` contracts — these exist to emit a port-keyed
//    map or a self-typed Item, which is the adaptive machinery again.
//
// There is also no fork. `action.wait_until`'s deferred branch runs hours later on the
// arm's job against a data snapshot; keeping a plugin process alive across that, or
// rebinding to resume it, is a second cross-process lifetime problem rather than a
// marshalling one. That one is refused rather than deferred.

/** An action with a typed data output. */
interface PluginAction<C : Any, O : Any> {
    val definition: PluginNodeDefinition<C, O>

    suspend fun execute(config: C, context: PluginContext): PluginOutput<O>
}

/** An action with no data output — it pulses and that is all. */
interface PluginEffect<C : Any> : PluginAction<C, Unit>

/** A trigger with a typed event payload. */
interface PluginTrigger<C : Any, O : Any> {
    val definition: PluginNodeDefinition<C, O>

    /**
     * Registers whatever this trigger watches and returns the handle that releases it.
     *
     * Not suspending, and callback-based rather than a `Flow`, so that a plugin author
     * needs no coroutines at all to write one. [emit] may be called from any thread and
     * at any time until [PluginArm.disarm].
     */
    fun arm(config: C, context: PluginContext, emit: (O) -> Unit): PluginArm
}

/** A trigger that carries no payload — it fires and that is all. */
interface PluginPulseTrigger<C : Any> : PluginTrigger<C, Unit>

/**
 * A pure leaf read, pulled just before whichever node consumes it.
 *
 * Held to the host's own value contract: no execution ports, no data inputs, and a
 * read that answers **null rather than throwing** when it cannot work — so the
 * consumer falls back to its form value and a comparison fails closed. Anything
 * expensive or failable belongs in an action instead, and the host enforces this with
 * a two-second timeout it does not give actions.
 */
interface PluginValue<C : Any, O : Any> {
    val definition: PluginNodeDefinition<C, O>

    suspend fun read(config: C, context: PluginContext): O?
}

/**
 * A pure function of its data inputs.
 *
 * Its inputs are its `@Wired` config properties: the host wires an item into one and
 * [NodeSchema][io.github.m1n1m1.easymatic.domain.registry.NodeSchema] resolves it ahead of
 * the form value, exactly as for a first-party node. A transform therefore needs at
 * least one `@Wired` property, or the host will reject it as a function of nothing.
 */
interface PluginTransform<C : Any, O : Any> {
    val definition: PluginNodeDefinition<C, O>

    suspend fun transform(config: C, context: PluginContext): O?
}

/**
 * Answers what a `@PluginChoice` field on this node may be set to.
 *
 * Implemented **in addition** to one of the four node contracts, never instead of one —
 * a chooser belongs to a node's config, so there is no such thing as a node that is only
 * a choice source.
 *
 * This is the boundary read from the other side. A plugin may not declare `@Picker`,
 * because every kind of picker names something of the *user's*; here the plugin is asked
 * about its **own** lists and is handed nothing at all — no host library, no context
 * beyond the one every node body already gets, and no capability. [source] is the key the
 * field declared, echoed back untouched, so one node can offer several lists.
 *
 * [config] carries **the siblings the field named in `scopedBy`, and nothing else** —
 * every other property holds its default. That is what makes
 * `@PluginChoice(scopedBy = ["workspace"])` work, and the restriction is deliberate
 * rather than stingy: the host clears a chosen value when a field it is *declared* to
 * depend on changes, so a chooser narrowing on an undeclared sibling would go on holding
 * an answer that had stopped being valid.
 *
 * Called while somebody waits, under a six-second bound, and **never during a run**.
 * Answer an empty list rather than throwing; the host shows a failure in the chooser
 * itself, where the person who can act on it is looking.
 */
interface PluginChoiceSource<C : Any> {

    suspend fun choices(source: String, config: C, context: PluginContext): List<OptionWire>
}

/**
 * Execution outputs this action names itself — `routes("out" to "Posted", "error" to "Couldn't post")`.
 *
 * **The first pair is the one that means *carried on*, and that is not a style rule.**
 * The host lands two things on it: a reply naming a route the declaration does not have,
 * and a call it could not make at all. The second is why it cannot be the failure route —
 * an unreachable plugin may well have done its work and failed only on the way back, so
 * the host must not claim it did not.
 */
fun routes(vararg routes: Pair<String, String>): ExecOutputsWire.Named =
    ExecOutputsWire.Named(routes.map { (name, label) -> RouteWire(name, label) })

/**
 * Declares an action with a typed data output.
 *
 * [typeId] is the short id — `"shout"`, not `"plugin:com.acme.tools/shout"`. The
 * service prefixes it with the plugin's own package name, so the one thing an author
 * cannot get wrong is the thing the host's namespacing rule rests on.
 *
 * [permissions] are the plugin's *own* manifest permissions. The host checks them
 * against the plugin's package and warns when one is missing; it never lends its own.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any, O : Any> pluginActionNode(
    typeId: String,
    displayName: String,
    description: String,
    icon: NodeIcon,
    output: DataOut<O>,
    execOutputs: ExecOutputsWire = ExecOutputsWire.Single,
    permissions: List<String> = emptyList(),
): PluginNodeDefinition<C, O> = PluginNodeDefinition(
    typeId = typeId,
    displayName = displayName,
    description = description,
    icon = icon,
    kind = NodeKind.ACTION,
    schema = nodeSchema<C>(),
    output = output,
    execOutputs = execOutputs,
    permissions = permissions,
    extraInputs = emptyList(),
)

/** Declares an action with no data output. */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any> pluginEffectNode(
    typeId: String,
    displayName: String,
    description: String,
    icon: NodeIcon,
    execOutputs: ExecOutputsWire = ExecOutputsWire.Single,
    permissions: List<String> = emptyList(),
): PluginNodeDefinition<C, Unit> = PluginNodeDefinition(
    typeId = typeId,
    displayName = displayName,
    description = description,
    icon = icon,
    kind = NodeKind.ACTION,
    schema = nodeSchema<C>(),
    output = null,
    execOutputs = execOutputs,
    permissions = permissions,
    extraInputs = emptyList(),
)

/** Declares a trigger with a typed event payload. */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any, O : Any> pluginTriggerNode(
    typeId: String,
    displayName: String,
    description: String,
    icon: NodeIcon,
    output: DataOut<O>,
    permissions: List<String> = emptyList(),
): PluginNodeDefinition<C, O> = PluginNodeDefinition(
    typeId = typeId,
    displayName = displayName,
    description = description,
    icon = icon,
    kind = NodeKind.TRIGGER,
    schema = nodeSchema<C>(),
    output = output,
    execOutputs = ExecOutputsWire.Single,
    permissions = permissions,
    extraInputs = emptyList(),
)

/** Declares a trigger that carries no payload. */
inline fun <reified C : Any> pluginPulseTriggerNode(
    typeId: String,
    displayName: String,
    description: String,
    icon: NodeIcon,
    permissions: List<String> = emptyList(),
): PluginNodeDefinition<C, Unit> = PluginNodeDefinition(
    typeId = typeId,
    displayName = displayName,
    description = description,
    icon = icon,
    kind = NodeKind.TRIGGER,
    schema = nodeSchema<C>(),
    output = null,
    execOutputs = ExecOutputsWire.Single,
    permissions = permissions,
    extraInputs = emptyList(),
)

/** Declares a pure leaf read. */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any, O : Any> pluginValueNode(
    typeId: String,
    displayName: String,
    description: String,
    icon: NodeIcon,
    output: DataOut<O>,
    permissions: List<String> = emptyList(),
): PluginNodeDefinition<C, O> = PluginNodeDefinition(
    typeId = typeId,
    displayName = displayName,
    description = description,
    icon = icon,
    kind = NodeKind.VALUE,
    schema = nodeSchema<C>(),
    output = output,
    execOutputs = ExecOutputsWire.Single,
    permissions = permissions,
    extraInputs = emptyList(),
)

/**
 * Declares a pure function of its data inputs.
 *
 * [extraInputs] declares a DATA input the node fills itself rather than deriving from
 * a `@Wired` config property — the same escape hatch `action.break`'s `struct` port
 * and `transform.convert`'s `value` port use, for the same reason: there is no form
 * row to opt in from, so the port is always shown.
 */
@Suppress("LongParameterList") // A node definition is intentionally a flat declaration DSL.
inline fun <reified C : Any, O : Any> pluginTransformNode(
    typeId: String,
    displayName: String,
    description: String,
    icon: NodeIcon,
    output: DataOut<O>,
    extraInputs: List<Port> = emptyList(),
): PluginNodeDefinition<C, O> = PluginNodeDefinition(
    typeId = typeId,
    displayName = displayName,
    description = description,
    icon = icon,
    kind = NodeKind.TRANSFORM,
    schema = nodeSchema<C>(),
    output = output,
    execOutputs = ExecOutputsWire.Single,
    // A transform may not require a permission — the host's own contract, enforced by
    // NodeDeclarationRules — so this builder does not offer the parameter.
    permissions = emptyList(),
    extraInputs = extraInputs,
)
