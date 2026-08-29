package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.HomeAssistantRef
import com.example.ottomatic.domain.model.SmartHomeRef
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.config.PickerKind

/**
 * What a read-only picker could be answered with, when something other than a person
 * is doing the answering.
 *
 * **This exists so an AI tool can leave a picker open.** A tool is a node type plus the
 * config its author pinned, and a `@Picker` field used to have to be pinned without
 * exception — the stated reason being that a model cannot invent a `SmartHomeRef` spec
 * or a UUID, and a mistyped one names nothing and merely looks broken. That reason is
 * sound and the conclusion was too narrow: the fix is not to make the author choose,
 * it is to **stop asking the model to invent**. Where the answer set is knowable, it
 * is handed over as an enumeration the provider itself enforces, so the model picks a
 * real scene out of a list and cannot produce anything else.
 *
 * Which kinds qualify is decided by CLAUDE.md's own test for a read-only picker —
 * **is the answer set knowable and complete?** — asked one layer further in: knowable
 * *from `domain`*, with no `Context`, no network and no suspension point. That is a
 * strictly smaller set than what the editor can offer, and the difference is not a
 * shortfall to be closed: an app list, a sound URI or a mail account is a fact about
 * the phone or about a credential, and neither belongs in a registry every validator
 * reads on every keystroke.
 *
 * **Empty means "cannot be enumerated", never "there is nothing"** — [Suggestions]'
 * degradation rule, and load-bearing in the same way. An unhydrated registry, a blank
 * scope and a kind nothing can list all answer empty, and the caller's response to
 * every one of them is identical: the field stays required-to-pin, exactly as it was
 * before this file existed. Nothing degrades into offering the model an empty choice.
 *
 * **Values are specs and never labels.** `SmartHomeRef` makes that free — the name is
 * the spec's last field, so `sh:hub|SCENE|rid|Dinner` reads as its own label to a model
 * and nothing has to translate a display name back to an id, which two scenes called
 * the same thing would break outright.
 */
object PickerOptions {

    /**
     * The values [kind] could take, scoped by [scope] — the values of the sibling
     * fields the property named, in declaration order, exactly as [Suggestions.of]
     * takes them.
     *
     * A blank or unparseable scope **widens** rather than empties: a tool whose hub is
     * unpinned offers every hub's entities, which is the honest answer to "which of
     * these may the model choose" when nothing has narrowed it.
     */
    @Suppress("CyclomaticComplexMethod") // One arm per PickerKind; the exhaustiveness is the point.
    fun of(kind: PickerKind, scope: List<String> = emptyList()): List<String> = when (kind) {
        PickerKind.LIGHT_TARGET -> lights()
        PickerKind.LIGHT_SCENE -> resources(SmartHomeTargetKind.SCENE)
        PickerKind.HA_ENTITY -> haEntities(scope)
        PickerKind.HA_SERVICE -> haServices(scope)
        PickerKind.MACRO -> macros()
        PickerKind.VARIABLE -> globalVariables()
        // Only the ones an appointment can be added to, for the target kind: offering a
        // model a subscribed holiday feed to write into is offering it a guaranteed
        // failure, which is what this whole file exists to stop. The filter kind is not
        // narrowed, because reading a read-only calendar is exactly what one is for.
        PickerKind.CALENDAR -> CalendarDirectory.writable().map { it.ref }
        PickerKind.CALENDAR_FILTER -> CalendarDirectory.all().map { it.ref }
        // The **downloaded** languages rather than the supported ones, which is the whole
        // difference between offering a model a translation that works and one that is
        // refused on its first run. Translation is the one thing this app does that cannot
        // happen at all without an asset being present, so the wider list would be a list
        // of guaranteed failures. Adding a language is the settings screen's job.
        PickerKind.TRANSLATE_LANGUAGE -> TranslateLanguages.downloaded()

        // ---- Deliberately not enumerable, each for its own reason. ----

        // Letting a model choose which model bills the user is not a gap to be filled.
        // This one would be trivial to answer and is refused on purpose.
        PickerKind.AI_MODEL -> emptyList()
        // Facts about the phone rather than about a library: the installed apps, the
        // ringtones, the tags that have been scanned, the folders that were granted.
        // None is published to `domain` and none should be, for the reason the class
        // KDoc gives.
        PickerKind.APP, PickerKind.APP_FILTER, PickerKind.SOUND, PickerKind.NFC_TAG -> emptyList()
        // Credentials and places live in repositories `domain` cannot reach, and a
        // projection of either would carry more than a chooser needs.
        PickerKind.MAIL_ACCOUNT, PickerKind.GEOFENCE_PLACE -> emptyList()
        // A hub is not a thing to pick *among* for a tool: `HA_HUB` and `MQTT_BROKER`
        // exist so a form can scope the field beneath them, and an unpinned hub already
        // widens that field to every hub — which is what the model wanted anyway.
        PickerKind.HA_HUB, PickerKind.MQTT_BROKER -> emptyList()
        // Home Assistant publishes triggers per entity over a websocket, so this is the
        // one HA kind that is not in the snapshot — `Suggestions.isLocal`'s line, drawn
        // in the same place for the same reason.
        PickerKind.HA_TRIGGER -> emptyList()
    }

    /** Every controllable light and group, which is what a light target may name. */
    private fun lights(): List<String> =
        resources(SmartHomeTargetKind.LIGHT) + resources(SmartHomeTargetKind.GROUP)

    private fun resources(kind: SmartHomeTargetKind): List<String> =
        SmartHomeHubs.ids().flatMap { hubId ->
            SmartHomeHubs.resources(hubId)
                .filter { it.kind == kind }
                .map { SmartHomeRef.format(hubId, it.kind, it.rid, it.name) }
        }

    /**
     * The entities of the scoped hub, or of every hub when none is named.
     *
     * Narrowed no further than by hub. `action.ha_service`'s entity field is also
     * scoped by its *service*, which the editor uses to hide entities that service
     * rejects — that filter is deliberately not reproduced here, on the rule
     * `HaService.targetDomains` states: partial scoping that offers slightly too much
     * is right, and offering too little is not.
     */
    private fun haEntities(scope: List<String>): List<String> =
        hubsIn(scope).flatMap { hubId ->
            HaCatalog.entities(hubId).map { HomeAssistantRef.format(hubId, it.entityId, it.name) }
        }

    private fun haServices(scope: List<String>): List<String> =
        hubsIn(scope).flatMap { hubId ->
            HaCatalog.services(hubId).map { service ->
                val id = "${service.domain}.${service.service}"
                HomeAssistantRef.format(hubId, id, service.name.ifBlank { id })
            }
        }

    /**
     * The hubs the scope names, or all of them.
     *
     * Every Home Assistant reference carries its hub, so any parseable one in the scope
     * narrows this — a pinned hub, a pinned entity or a pinned service all work, which
     * is what makes "pin the entity and let it choose the service" behave.
     */
    private fun hubsIn(scope: List<String>): List<String> {
        val named = scope.mapNotNull { HomeAssistantRef.parse(it)?.hubId }.filter { it.isNotBlank() }
        return named.distinct().ifEmpty { HaCatalog.hubIds() }
    }

    /**
     * Every macro, by id.
     *
     * Offered even though a macro is also its own tool tier: a `@Picker(MACRO)` field
     * is a node acting *on* a macro — enabling it, disabling it — which is a different
     * thing from calling one, and "switch the holiday macro off" is a sentence somebody
     * would reasonably want the model to be able to act on.
     */
    private fun macros(): List<String> = MacroDirectory.all().map { it.id }

    /**
     * The **global** variables only.
     *
     * A tool runs from no workflow, so a workflow-local declaration is not merely
     * unavailable here — it would be meaningless, since nothing would resolve it. The
     * narrowing is therefore forced rather than chosen, and it is the right one:
     * a variable an AI is meant to read or write is one that outlives a single run.
     */
    private fun globalVariables(): List<String> =
        GlobalVariables.declarations.map { VariableRef.globalSpec(it.id) }
}
