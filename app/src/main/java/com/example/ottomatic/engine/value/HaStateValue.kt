package com.example.ottomatic.engine.value

import com.example.ottomatic.domain.model.HomeAssistantRef
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Suggested
import com.example.ottomatic.domain.model.config.SuggestionSource
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.valueNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class HaStateValueConfig(
    @Label("Entity")
    @Picker(PickerKind.HA_ENTITY)
    val entity: String = "",
    @Label("Attribute (optional)")
    @Suggested(SuggestionSource.HA_ENTITY_ATTRIBUTE, scopedBy = ["entity"])
    val attribute: String = "",
)

/**
 * What a Home Assistant entity is right now.
 *
 * **The node that looks like it breaks the purity rule and does not**, and the argument
 * is worth having in full because `SmartHome`'s KDoc says the opposite about lights.
 *
 * There is no `value.light_state` because every member of that facade is a network round
 * trip — slow and failable, which are exactly the two things the pull side may not be. A
 * value is read *just before each consumer*, memoized per consuming node, so two
 * `action.if`s comparing the same thing would be two round trips on the exec path with
 * nothing on the canvas disclosing them.
 *
 * This one reads a `ConcurrentHashMap`. A websocket is held open for every configured
 * hub for as long as the engine process runs, pushing every `state_changed` into it, so
 * the read is a lookup: cheap, repeatable and incapable of failing. **The bar was never
 * "must not concern the network"** — it was "must be cheap and must not fail" — and a
 * push channel clears it where a request-response API cannot. That is the general lesson
 * for a future integration: what decides the pull side is the *shape of the transport*,
 * not the subject matter.
 *
 * **Text out, not a struct.** One DATA output is the value contract, and the question
 * people ask is "is the door open?" or "what is the temperature?". A struct would put an
 * `action.break` in front of every comparison — the ceremony `trigger.message`'s
 * projection paragraph objects to. A number arriving as text is what autocast is for: a
 * `transform.convert` appears in the wire, visibly, with its own "If it fails" field.
 *
 * **Null when it cannot answer**, which covers a hub that was never chosen or has been
 * deleted, an entity the cache has never seen, and a connection that never came up. The
 * consumer then falls back to its own form value and a comparison fails closed — the
 * declared degradation for a value that cannot read, and the same one
 * `value.wifi_network` gives when its permission is missing.
 *
 * **No permission declared**, on `action.light_control`'s rule: `INTERNET` is
 * install-time and gets no `Permissions` constant. What actually stops this node — no
 * hub, a revoked token — is reported where it belongs, and is a fact about setup rather
 * than about the phone.
 */
class HaStateValue : ValueNode<HaStateValueConfig, String> {

    override val definition = valueNode<HaStateValueConfig, String>(
        typeId = "value.ha_state",
        displayName = "Home Assistant Entity",
        description = "The current state of a Home Assistant entity — a sensor, a switch, a thermostat",
        category = NodeCategory.VALUE_SMART_HOME,
        icon = NodeIcon.HOME,
        output = dataOut("state", label = "State"),
    )

    @Suppress("ReturnCount") // Three "cannot answer" cases, all of which mean null.
    override suspend fun read(config: HaStateValueConfig, context: ExecutionContext): String? {
        val reference = HomeAssistantRef.parse(config.entity) ?: return null
        if (reference.id.isBlank()) return null
        val reading = context.homeAssistant.state(reference.hubId, reference.id) ?: return null
        val attribute = config.attribute.trim()
        return if (attribute.isBlank()) reading.state else attributeOf(reading.attributes, attribute)
    }

    /**
     * One attribute out of the entity's JSON, or null when it has none by that name.
     *
     * Here rather than left to `transform.json_read` because the commonest reads on the
     * pull side are one level deep — a light's `brightness`, a media player's
     * `media_title`, a climate entity's `current_temperature` — and a value node
     * compared inside an `action.if` has no wire to hang a transform on when it is used
     * as a `val:` source. Anything nested is still the transform's job, and the full
     * JSON reaches it through `trigger.ha_state`.
     *
     * A missing attribute answers **null rather than blank**, so it degrades the same way
     * a missing entity does instead of comparing equal to an empty string.
     */
    private fun attributeOf(attributes: String, name: String): String? = runCatching {
        Json.parseToJsonElement(attributes).jsonObject[name]?.let { element ->
            // A string attribute unquoted, anything else as written — so a number
            // compares as a number after autocast and an object survives to json_read.
            element.jsonPrimitive.contentOrNull ?: element.toString()
        }
    }.getOrNull()
}
