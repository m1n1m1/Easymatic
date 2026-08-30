package io.github.m1n1m1.easymatic.engine.api

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.PortSpec
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.model.schema.jsonElementToString
import io.github.m1n1m1.easymatic.nodeapi.plugin.PluginLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Turns the flat `in.<name>` values a calling app sent into the typed items a
 * `trigger.api` node's declared ports carry.
 *
 * It takes a `Map<String, String>` rather than a `Bundle`, and that is the whole
 * reason this is a file of its own: a `Bundle` is a platform class with no
 * constructor outside an instrumented test, and every interesting rule here is
 * about *meaning* rather than about IPC. Both front doors flatten their own
 * transport to this shape first, which additionally means the two cannot disagree
 * about what `in.city` does.
 *
 * Everything here is deliberately forgiving, on `PortSpec.parse`'s reasoning: the
 * text arrives from another app entirely, so there is nothing to be gained by
 * failing a whole run over one unreadable value that the macro may not even read.
 * Nothing throws.
 */
object ApiInputs {

    /**
     * The largest single value accepted, in UTF-8 bytes.
     *
     * `PluginLimits`' figure rather than one of our own, and for the same reason:
     * everything here crosses a binder, where a transaction over roughly a megabyte
     * is a `TransactionTooLargeException` in somebody else's process. Sharing the
     * constant is what stops the two boundaries drifting into disagreeing about how
     * big is too big.
     */
    const val MAX_VALUE_BYTES = PluginLimits.MAX_ITEM_BYTES

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val TEXT: ItemSchema.Primitive = ItemSchema.Primitive(String::class)

    /**
     * The items for [specs], read out of [raw] by port name.
     *
     * Four rules, each of which is a test:
     *
     * - **Only declared names are read.** An extra `in.whatever` is dropped in
     *   silence rather than reported, because a caller written against a newer
     *   version of a macro is a completely ordinary thing and not an error.
     * - **An absent name contributes no entry at all**, rather than a zero value.
     *   That is what makes an unsent input behave exactly like an unwired port —
     *   the executor caches nothing, and the consumer falls back the way it always
     *   does. A `0` would be indistinguishable from a caller that meant zero.
     * - **An oversize value is dropped**, not truncated. Half a JSON document is
     *   worse than none.
     * - **Everything else lands on its declared type**, through the total
     *   `ValueType.convert`, so `in.count=banana` becomes `0` rather than failing
     *   the call. The macro sees exactly what a `transform.convert` with an empty
     *   fallback would have produced.
     *
     * An **untyped (`ANY`) port receives text**, which is the one place this is
     * narrower than `action.script`'s equally-untyped inputs. A script's `ANY` exists
     * to receive a whole struct *from another node in the graph*, where everything
     * arriving here has already been flattened to a string by the transport. A
     * caller that wants structure sends JSON and the macro reads it with
     * `transform.json_read` — `action.http`'s road, and the same one a plugin's
     * struct output takes.
     */
    fun read(raw: Map<String, String>, specs: List<PortSpec>): Map<PortName, Item> =
        specs.mapNotNull { spec ->
            val text = raw[spec.name] ?: return@mapNotNull null
            if (text.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) return@mapNotNull null
            PortName(spec.name) to itemFor(spec, text)
        }.toMap()

    private fun itemFor(spec: PortSpec, text: String): Item = when {
        spec.list -> Item(value = elements(text).map { valueOf(spec, it) }, schema = spec.schema)
        else -> single(spec, text)
    }

    private fun single(spec: PortSpec, text: String): Item =
        spec.type?.convert(Item(value = text, schema = TEXT)) ?: Item(value = text, schema = TEXT)

    /** The raw Kotlin value a list element carries — lists hold values, not nested [Item]s. */
    private fun valueOf(spec: PortSpec, text: String): Any? = single(spec, text).value

    /**
     * A list value's elements.
     *
     * JSON is the interchange the rest of this door already speaks, so an array is
     * the natural spelling. Text that is not an array becomes a **one-element list**
     * rather than an empty one, because `in.tags=urgent` is what somebody sending a
     * single value will write, and reading it as "no tags" would be silently wrong
     * where reading it as "one tag" is silently right.
     */
    private fun elements(text: String): List<String> {
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            ?: return listOf(text)
        return array.map(::jsonElementToString)
    }
}
