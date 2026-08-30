package io.github.m1n1m1.easymatic.core.service

/**
 * What a model may *do*, as the engine describes it.
 *
 * **Vendor-neutral by the same test [Ai] itself passes**: Gemini calls this a
 * `functionDeclaration`, Anthropic a `tool` with an `input_schema`, OpenAI a
 * `function` with `parameters`, and all three want the same three facts — a name, a
 * sentence saying when to use it, and a JSON-Schema object describing its arguments.
 * So the three facts live here and the three renderings live in `data/ai/`, exactly
 * as `thinkingLevel` / `thinking` / `reasoning_effort` are three renderings of one
 * idea.
 *
 * [name] must satisfy every provider's shared constraint — letters, digits,
 * underscore and hyphen, at most [MAX_NAME_LENGTH] characters — which a node's
 * typeId does *not*, since `action.notify` contains a dot. [sanitizeName] is the one
 * place that is fixed, so a caller building tools out of typeIds never has to know
 * the rule.
 */
data class AiTool(
    val name: String,
    /**
     * When to use this, in the model's terms rather than the app's.
     *
     * This is the highest-leverage string in the whole feature: a model reaches for
     * a tool almost entirely on its description, and a vague one produces a tool
     * that is never called or called for the wrong thing.
     */
    val description: String,
    val parameters: List<AiParam> = emptyList(),
) {
    companion object {

        /** The longest name every provider here accepts. */
        const val MAX_NAME_LENGTH = 64

        /**
         * [raw] reduced to the character set every provider accepts.
         *
         * Deliberately **not** a validator that rejects: the callers build names out
         * of typeIds and macro names, neither of which the user chose with this rule
         * in mind, and refusing them would turn "you named a macro with a space in
         * it" into a tool that silently does not exist.
         */
        fun sanitizeName(raw: String): String {
            val cleaned = raw.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
                .joinToString(separator = "")
                .take(MAX_NAME_LENGTH)
                .trim('_')
            return cleaned.ifBlank { "tool" }
        }
    }
}

/** One argument a tool takes. */
data class AiParam(
    val name: String,
    val schema: AiParamSchema,
    val description: String = "",
    /**
     * Whether the model must supply this.
     *
     * Almost always false here, and that is a fact about Easymatic rather than a
     * default chosen for safety: every node config property has a declared default
     * by construction, so a missing argument is not an error but the default being
     * used. Marking everything required would make the model invent values for
     * fields the author was happy to leave alone.
     */
    val required: Boolean = false,
)

/**
 * The shape of one argument.
 *
 * A deliberately small closed set — the five scalar families the app's own
 * `ConfigFieldType` reduces to, plus a list of them. There is no struct member,
 * because nothing produces one: a node's config is flat by rule (every property is a
 * scalar) and a macro's declared inputs are flat for the same reason. Adding one
 * later is a member and one branch per protocol.
 */
sealed interface AiParamSchema {

    /** Free text, or one of [options] when the answer set is closed. */
    data class Text(val options: List<String> = emptyList()) : AiParamSchema

    data object Integer : AiParamSchema

    data object Decimal : AiParamSchema

    data object Flag : AiParamSchema

    data class Items(val element: AiParamSchema) : AiParamSchema
}

/**
 * A model asking for a tool to be run.
 *
 * [arguments] are **text**, which is not a loss of type information but the app's own
 * representation: `NodeSchema.decode` takes a `Map<ConfigKey, String>` and parses
 * leniently, config values are text everywhere in Easymatic, and a value that arrived
 * as a JSON number is rendered back to its text form by the same rule
 * `Item.asText()` follows. Handing a `JsonElement` up to `core/` would put a
 * serialization type in the facade for no caller that wants one.
 *
 * [id] is what the answer is correlated by. Anthropic and OpenAI both mint one and
 * require it echoed; Gemini matches on [name] instead and the id is synthesized, which
 * costs nothing and keeps one shape here.
 */
data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
)

/**
 * What running a tool produced, as the model will read it.
 *
 * [isError] does not mean the conversation failed — it means *this tool* did, and the
 * model is told so in the terms the provider has for it. That is the whole point of
 * reporting it rather than throwing: a model that asked for a light that is
 * unreachable should hear "unreachable" and try something else, exactly as a person
 * would, where an exception would end the run.
 */
data class AiToolResult(
    val text: String,
    val isError: Boolean = false,
)

/**
 * How long a tool-using exchange may run.
 *
 * A cap on turns is [io.github.m1n1m1.easymatic.core.service.Ai]'s version of
 * `MAX_ITERATIONS`, and for the identical reason: this loop is driven by the model
 * rather than by the graph, so nothing else bounds it, and an unattended macro that
 * loops on a misunderstanding bills for every turn of it. Hitting the cap is reported
 * rather than silently truncated.
 */
object AiToolLimits {

    /**
     * Enough for a read, a decision, an action and a summary — with room to be wrong
     * once about each. Raising it is a per-node choice; most macros never reach four.
     */
    const val DEFAULT_MAX_TURNS = 8

    /** The ceiling the node's own field is clamped to. */
    const val MAX_TURNS = 24

    /**
     * The whole exchange's budget, checked before each turn rather than enforced by
     * cancelling one.
     *
     * The per-request read timeout is already ninety seconds, so the turn cap alone
     * permits a run of well over ten minutes — which a macro firing on a trigger
     * should not be able to do by accident. Checked *between* turns so a tool that
     * is halfway through switching a light off is never cancelled mid-write.
     */
    const val OVERALL_BUDGET_MS = 5L * 60L * 1_000L
}
