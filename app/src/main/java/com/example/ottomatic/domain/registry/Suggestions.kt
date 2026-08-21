package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.HomeAssistantRef
import com.example.ottomatic.domain.model.HubRef
import com.example.ottomatic.domain.model.config.SuggestionSource

/**
 * What to offer in a field whose answer set is **known but not closed**.
 *
 * The generic half of the scoping mechanism. A `@Suggested` property names a
 * [SuggestionSource] and the sibling fields that scope it; this turns that pair into a list of
 * suggestions, and the widget renders an editable field with them in a dropdown beside it.
 *
 * **One exhaustive `when`, and that is the whole test of whether this is generic.** A future
 * integration adds a [SuggestionSource] member and a branch here and touches nothing else — not
 * the annotation, not `NodeSchema`, not `ConfigFieldEditor`, not the widget. That is what the
 * mechanism is for, and it is why this did not become `@HaSuggestion` with Home Assistant baked
 * into its name.
 *
 * **Everything here is a stored value, never a label.** A chooser is free to render `on` as
 * "Open" for a door sensor; what it writes must be what this returns, because that is what
 * `HaStateTrigger.matches` compares against. A resolver that returned display text would
 * produce a trigger that reads perfectly and never fires.
 *
 * **Empty means "I cannot narrow this", never "there is nothing".** An unhydrated catalogue, a
 * blank scope, a numeric sensor whose states nothing can enumerate — all answer empty, and the
 * field then behaves exactly as the plain text box it was before. That is the degradation rule
 * the whole change depends on, stated once, here.
 */
object Suggestions {

    /**
     * The suggestions for [source], scoped by [scope] — the values of the sibling fields the
     * property named, in declaration order.
     *
     * [scope] is a list rather than one value because a field may be narrowed by more than one
     * sibling: `action.ha_service`'s service is scoped by both its hub and its entity.
     */
    fun of(source: SuggestionSource, scope: List<String>): List<String> = when (source) {
        SuggestionSource.HA_ENTITY_ATTRIBUTE -> entityAttributes(scope)
        SuggestionSource.MAIL_FOLDER -> emptyList()
        SuggestionSource.MQTT_TOPIC -> brokerTopics(scope)
        SuggestionSource.SPEECH_LANGUAGE -> SpeechLanguages.forSpeaking()
        SuggestionSource.RECOGNITION_LANGUAGE -> SpeechLanguages.forListening()
    }

    /**
     * Whether [source] can be answered without a network round trip.
     *
     * `MAIL_FOLDER` cannot: a mailbox list is an authenticated IMAP `LIST`, so its widget asks
     * the ViewModel rather than this. Keeping it a member of the enum anyway is deliberate —
     * the *declaration* is uniform even where the fetching is not, which is what lets a node
     * author write `@Suggested(MAIL_FOLDER, scopedBy = ["accountId"])` without knowing or
     * caring where the answer comes from.
     */
    fun isLocal(source: SuggestionSource): Boolean = when (source) {
        SuggestionSource.HA_ENTITY_ATTRIBUTE -> true
        SuggestionSource.MAIL_FOLDER -> false
        // A broker publishes no directory, so this cannot be fetched on demand either:
        // what there is to offer is what a Refresh already recorded on the hub.
        SuggestionSource.MQTT_TOPIC -> true
        // Both are published into `SpeechLanguages` by `AndroidSpeech`; asking the
        // platform costs an engine init, which is precisely what is not done on demand.
        SuggestionSource.SPEECH_LANGUAGE -> true
        SuggestionSource.RECOGNITION_LANGUAGE -> true
    }

    /**
     * The topics the scoped broker was last heard publishing.
     *
     * Empty for a broker that has never been refreshed, which leaves the field exactly as
     * it was before it was scoped — a plain text box. That is the degradation rule doing
     * its job rather than a failure, and it matters more here than anywhere else it is
     * stated: this list is *structurally* incomplete, so a field that could only hold what
     * is in it would be unusable.
     */
    private fun brokerTopics(scope: List<String>): List<String> =
        scope.firstNotNullOfOrNull { HubRef.parse(it) }
            ?.let { MqttCatalog.topics(it.hubId) }
            .orEmpty()

    /** The attribute names the scoped entity published. */
    private fun entityAttributes(scope: List<String>): List<String> =
        entityOf(scope)?.attributes.orEmpty()

    /**
     * The entity the scope names, or null.
     *
     * The **last** parseable entity reference wins rather than the first, because a scope list
     * may hold a hub reference too and a hub's reference carries a blank id by construction —
     * so this can never mistake one for the other.
     */
    private fun entityOf(scope: List<String>) = scope
        .mapNotNull { HomeAssistantRef.parse(it) }
        .lastOrNull { it.id.isNotBlank() }
        ?.let { HaCatalog.entity(it.hubId, it.id) }
}
