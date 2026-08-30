package io.github.m1n1m1.easymatic.feature.i18n

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.registry.ConfigField
import io.github.m1n1m1.easymatic.domain.registry.ConfigOption

/**
 * Translated text for the parts of the node system whose answer set is **open-ended**.
 *
 * Text with a closed answer set does not come through here — `NodeCategory` and the
 * permission copy take the direct `R.string` route, which is compile-checked. What is
 * left is per-node and cannot: a node declares its text as a plain Kotlin literal and
 * always will, since `@Label` is an annotation and much of this lives in `:node-api`,
 * which is compiled without `android.jar`. So the literal stays where it is, the key
 * is *derived* from the typeId the declaration already carries, and this class reads
 * it back.
 *
 * **A key that resolves to nothing falls back to the literal** — the one fallback in
 * the app, and load-bearing rather than defensive: a plugin node's text crossed the
 * binder already rendered by its author, so no key exists for it or ever will.
 *
 * It takes a lookup rather than a [Resources] so every method stays pure and
 * JVM-testable. See "Node text and translation" in CLAUDE.md.
 */
internal class NodeText(private val lookup: (String) -> String?) {

    /** What the node is called, on its card and in the palette. */
    fun name(definition: NodeTypeDefinition): String =
        lookup("node_${definition.typeId.slug()}_name") ?: definition.displayName

    /** The one-line explanation under the name in the palette. */
    fun description(definition: NodeTypeDefinition): String =
        lookup("node_${definition.typeId.slug()}_desc") ?: definition.description

    /**
     * The name beside a socket.
     *
     * Takes the owning [typeId] because a [Port] does not carry one, and the same port
     * name means different things on different nodes. Ports synthesised at runtime —
     * `action.break`'s struct fields, `action.script`'s named inputs, `trigger.api`'s
     * user-named outputs — have no key and fall back, which is correct: the user chose
     * those words themselves.
     */
    fun portLabel(typeId: NodeTypeId, port: Port): String =
        lookup("port_${typeId.slug()}_${port.name.value.snake()}") ?: port.label

    /** The label on a form row. */
    fun fieldLabel(typeId: NodeTypeId, field: ConfigField<*>): String =
        lookup("cfg_${typeId.slug()}_${field.key.value.snake()}") ?: field.label

    /**
     * The sentence explaining a form row, or blank when the name already says it.
     *
     * A separate key family rather than a second half of the label's, because the two are read in
     * different places — the label in the outline's notch, this outside it — and a field that
     * gains or loses its explanation must not disturb the key its label already has.
     */
    fun fieldHint(typeId: NodeTypeId, field: ConfigField<*>): String =
        lookup("hint_${typeId.slug()}_${field.key.value.snake()}") ?: field.hint

    /**
     * One choice in a form row's dropdown.
     *
     * A blank value is the "not set" option every nullable enum field offers, and it
     * is one string rather than one per field — `NodeSchema.UNSET_LABEL` spells it
     * once, so translating it once is the matching move.
     */
    fun optionLabel(typeId: NodeTypeId, field: ConfigKey, option: ConfigOption): String {
        val key = if (option.value.isBlank()) {
            "config_option_unset"
        } else {
            "opt_${typeId.slug()}_${field.value.snake()}_${option.value.snake()}"
        }
        return lookup(key) ?: option.label
    }

    /**
     * One choice in a `@Ports` row's type dropdown.
     *
     * Not keyed per node: these rows are not config fields, so there is no typeId to
     * hang a key on, and a [ValueType] means the same thing everywhere it is offered.
     * Sharing the key is also what keeps "Date & time" from being spelled twice.
     */
    fun valueTypeLabel(option: ConfigOption): String =
        lookup("valuetype_${option.value.snake()}") ?: option.label

    companion object {
        /** Reads through [resources]; answers null for anything it has no string for. */
        fun of(resources: Resources) = NodeText { key ->
            NodeStringIds.byKey[key]?.let { runCatching { resources.getString(it) }.getOrNull() }
        }
    }
}

/** The [NodeText] for the current composition. */
@Composable
internal fun rememberNodeText(): NodeText {
    val resources = LocalContext.current.resources
    return remember(resources) { NodeText.of(resources) }
}

/** `action.send_sms` → `action_send_sms`. A resource name may not contain a dot. */
internal fun NodeTypeId.slug(): String = value.replace('.', '_')

/**
 * `daysOfWeek` → `days_of_week`, `PLAY_PAUSE` → `play_pause`.
 *
 * Shared with the generator, which must spell every key exactly the same way. A
 * second implementation would drift by one underscore, and the symptom is not an
 * error — every lookup just falls back to English, which looks like nothing is wrong.
 */
internal fun String.snake(): String =
    replace(CAMEL_BOUNDARY, "_").replace('-', '_').lowercase()

private val CAMEL_BOUNDARY = Regex("(?<=[a-z])(?=[A-Z])")
