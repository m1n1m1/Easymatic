package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.ScriptConsoleLevel
import com.example.ottomatic.core.service.ScriptOutcome
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.PortSpec
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Ports
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.anyToJsonElement
import com.example.ottomatic.domain.registry.SCRIPT_TYPE_ID
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.adaptiveNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Config for `action.script`.
 *
 * Nothing here is `@Wired`. The script's *values* arrive on the ports named by
 * [inputs] as real items, so wiring one of these fields would only route it
 * through the text round-trip
 * [com.example.ottomatic.domain.registry.NodeSchema.decode] performs and lose
 * the type on the way in.
 *
 * [inputs] defaults to empty and [outputs] to the same thing
 * [PortSpec.parseOutputs] falls back to, because a blank config value decodes to
 * the property's *default* rather than to blank. Any other default would mean a
 * user who deleted every input row got one back, and would make the ports
 * `effectivePorts` derives from the raw config disagree with the ones the script
 * actually binds.
 */
@Serializable
data class ScriptConfig(
    @Label("Inputs") @Ports val inputs: String = "",
    @Label("Script")
    @Hint("each input is a variable of its own name")
    @Multiline
    val script: String = "return { result: \"hello\" }",
    @Label("Outputs") @Ports val outputs: String = "result:TEXT",
    @Label("Timeout (ms)") val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    @Label("If it fails") val fallback: String = "",
)

/**
 * `action.script` — runs a piece of JavaScript and puts what it returns on ports
 * the user names.
 *
 * The graph's escape hatch. Everything else here is a node with a fixed meaning;
 * this is the one place a user writes an arbitrary expression, for the things a
 * palette can never cover — arithmetic over two readings, pulling a code out of
 * an SMS, reshaping an API response.
 *
 * **An action, not a transform.** The pull side is for reads that are cheap and
 * cannot fail; an evaluation is cross-process, can throw, can time out, and on a
 * device with no usable WebView cannot happen at all. So it takes an execution
 * position like `action.http`, where a run log can say what went wrong.
 *
 * **Its ports are named rather than derived.** `action.break` learns its ports
 * from the struct wired into it, but a script's shape is only known to whoever
 * wrote it. So both sides come from config: each input becomes a variable of its
 * own name, and the script returns an object keyed by the output names. A script
 * declaring exactly one output may also return the bare value — `return 42` is
 * what someone writes first, and refusing it teaches nothing.
 *
 * **An input may name a type or take anything.** "Anything" is a wildcard, which
 * is what lets a whole `HttpResponseItem` arrive as a real JavaScript object.
 * Naming a type instead buys the port its colour and a real type check, so a
 * mis-wired script is a refused drop rather than a confusing `NaN`.
 *
 * **A failure is never fatal.** An unavailable engine, a syntax error, a runaway
 * loop and a missing key all land on [ScriptConfig.fallback] and pulse `out`,
 * the same stance `transform.json_read` takes. Acting on "the script failed"
 * means comparing its output with `action.if`, which is visible on the canvas;
 * silently halting a macro from inside a text field would not be.
 */
class ScriptAction : RawAction<ScriptConfig> {

    override val definition = adaptiveNode<ScriptConfig>(
        typeId = SCRIPT_TYPE_ID.value,
        displayName = "Run Script",
        description = "Runs JavaScript over inputs you name and returns values on ports you name",
        category = NodeCategory.DATA,
        icon = NodeIcon.CODE,
        // No declared data ports at all: both sides are named in config and
        // resolved by `scriptEffectivePorts`, so a script that reads nothing has
        // no input handles rather than unused ones.
        extraPorts = emptyList(),
    )

    override suspend fun executeRaw(
        config: ScriptConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        val outputs = PortSpec.parseOutputs(config.outputs)
        val outcome = context.scripts.evaluate(
            source = wrap(config.script, PortSpec.parse(config.inputs), input),
            timeoutMs = config.timeoutMs.toLong().coerceAtLeast(1),
        )
        // Before the outcome's own line, so `console.log("dividing"); boom()`
        // reads in the order it happened rather than ending with its own preface.
        outcome.console.forEach { context.log("console: ${it.message}", levelOf(it.level)) }
        val returned = resultOf(outcome, context)
        return NodeOutput(outputs.associate { spec -> PortName(spec.name) to item(spec, returned, outputs, config) })
    }

    /**
     * The value the script returned, or null once the failure has been logged.
     *
     * The two failure shapes are kept apart in the log on purpose: an
     * [ScriptOutcome.Unavailable] is the phone's fault and no amount of editing
     * the script will fix it, so it must not read like a mistake the user made.
     */
    private fun resultOf(outcome: ScriptOutcome, context: ExecutionContext): JsonElement? = when (outcome) {
        is ScriptOutcome.Unavailable -> {
            context.log("Run Script: this device cannot run scripts (no compatible WebView)", LogLevel.WARN)
            null
        }
        is ScriptOutcome.Error -> {
            context.log("Run Script failed: ${outcome.message}", LogLevel.ERROR)
            null
        }
        is ScriptOutcome.Value -> unwrap(outcome.json, context)
    }

    /**
     * A `console.log` is deliberate output, exactly like `action.log` — so it
     * lands at [LogLevel.INFO] and is visible at the console's default filter.
     * Sending it to DEBUG would hide the one thing the user wrote the line for.
     */
    private fun levelOf(level: ScriptConsoleLevel): LogLevel = when (level) {
        ScriptConsoleLevel.ERROR -> LogLevel.ERROR
        ScriptConsoleLevel.WARNING -> LogLevel.WARN
        else -> LogLevel.INFO
    }

    /** Reads the `{ok, value, error}` envelope [wrap] makes the script produce. */
    private fun unwrap(json: String, context: ExecutionContext): JsonElement? {
        val envelope = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
        return when {
            envelope == null -> {
                context.log("Run Script: the engine returned something unreadable", LogLevel.ERROR)
                null
            }
            (envelope[OK_KEY] as? JsonPrimitive)?.booleanOrNull != true -> {
                val message = (envelope[ERROR_KEY] as? JsonPrimitive)?.content ?: "unknown error"
                context.log("Run Script threw: $message", LogLevel.ERROR)
                null
            }
            else -> envelope[VALUE_KEY]?.takeIf { it !is JsonNull }
        }
    }

    /**
     * One output port's item, taken from [returned] by name.
     *
     * Routed through [com.example.ottomatic.domain.model.config.ValueType.convert]
     * — the same total conversion `transform.json_read` uses — so a JSON number,
     * a JSON string and a nested object all reach a port the same way, and a
     * missing key lands on the fallback instead of failing the run.
     */
    private fun item(
        spec: PortSpec,
        returned: JsonElement?,
        outputs: List<PortSpec>,
        config: ScriptConfig,
    ): Item {
        val found = when {
            returned == null -> null
            // Sole output: a bare `return 42` means that value, not a lookup of
            // a key the script never wrote.
            outputs.size == 1 && returned !is JsonObject -> returned
            else -> (returned as? JsonObject)?.get(spec.name)
        }
        if (spec.list) return listItem(spec, found, config)
        // An untyped port passes the raw JSON through: `Item.asText()` renders it
        // the same way, so it still reads correctly downstream, and there is no
        // type to convert it to.
        return spec.type?.convert(
            item = found?.let { Item(value = it, schema = ItemSchema.Wildcard) },
            fallback = config.fallback,
        ) ?: Item(value = found ?: JsonPrimitive(config.fallback), schema = ItemSchema.Wildcard)
    }

    /**
     * A list output port's item: the returned JavaScript array, element by element
     * through the same total conversion the scalar branch uses.
     *
     * Anything that is not an array reads as the empty list. A script that declares
     * a list port and returns a single value has a bug in it, and quietly wrapping
     * that value would hide it behind a loop that ran exactly once.
     */
    private fun listItem(spec: PortSpec, found: JsonElement?, config: ScriptConfig): Item {
        val elements = (found as? JsonArray).orEmpty()
        val type = spec.type
            ?: return Item(elements.toList(), ItemSchema.ListSchema(ItemSchema.Wildcard))
        val converted = elements.map { element ->
            type.convert(Item(value = element, schema = ItemSchema.Wildcard), config.fallback).value
        }
        return Item(converted, ItemSchema.ListSchema(type.schema))
    }

    /**
     * Wraps the user's [script] so the platform can hand back a result at all.
     *
     * `evaluateJavaScriptAsync` returns empty text for anything that is not a
     * JavaScript `String`, so the value has to be stringified here rather than by
     * whoever wrote the code. The `try`/`catch` is on this side of the boundary
     * for the same reason: a `TypeError` caught in JavaScript arrives as a
     * readable sentence, where one left to propagate arrives as a platform
     * exception nobody can act on.
     *
     * The inputs are inlined as JSON literals under their own names, keeping
     * their types — a number is a number in the script, and a struct is an object
     * with its fields. Declaring an input is what makes its variable exist, so an
     * unwired one is `null` rather than a `ReferenceError`.
     */
    private fun wrap(script: String, inputs: List<PortSpec>, input: NodeInput): String {
        val bindings = inputs.joinToString(separator = " ") { spec ->
            "var ${spec.name} = ${literal(input.item(PortName(spec.name)))};"
        }
        return """
            (function () {
              "use strict";
              $bindings
              try {
                var __result = (function () { $script })();
                return JSON.stringify({
                  $OK_KEY: true,
                  $VALUE_KEY: __result === undefined ? null : __result
                });
              } catch (e) {
                return JSON.stringify({ $OK_KEY: false, $ERROR_KEY: String(e) });
              }
            })()
        """.trimIndent()
    }

    /** An input as a JSON literal, or `null` when the port is unwired. */
    private fun literal(item: Item?): String {
        if (item == null || item.value == null) return "null"
        return anyToJsonElement(item.value, item.schema).toString()
    }

    private companion object {
        const val OK_KEY = "ok"
        const val VALUE_KEY = "value"
        const val ERROR_KEY = "error"
    }
}

/**
 * Two seconds. Long enough for anything a macro legitimately computes, short
 * enough that a mistake does not leave a trigger wedged — a script has no I/O to
 * wait on, so a slow one is a looping one.
 */
private const val DEFAULT_TIMEOUT_MS = 2_000
