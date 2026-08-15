package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.PhoneRef
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.PickerKind

/**
 * Which of a node type's config keys hold a reference to something outside the node.
 *
 * Each is derived from the node's own config class rather than listed by hand, so a
 * second node that takes a macro or a phone number is covered by the validator and
 * the permission notice the moment it declares the annotation — which is the only
 * registration step the rest of the node system needs either. [variableRefKeys] in
 * `VariableRepair.kt` is the same idea and came first.
 */

/** The config keys of [typeId] that hold a macro id. */
fun macroRefKeys(typeId: NodeTypeId): List<ConfigKey> =
    ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
        .filter { (it.type as? ConfigFieldType.PICKER)?.kind == PickerKind.MACRO }
        .map { it.key }

/**
 * The config keys of [typeId] that hold a reference to something on a smart-home hub —
 * a [com.example.ottomatic.domain.model.SmartHomeRef] or a
 * [com.example.ottomatic.domain.model.HomeAssistantRef].
 *
 * **All five kinds**, because the question the validator asks of them is one question:
 * is the hub inside this reference still set up? Which section of which hub the
 * reference names is the picker's business and not this one's — and that was already
 * the stated reason the two light kinds shared a list, so Home Assistant's three join
 * it rather than getting a second function that would ask the same thing.
 *
 * The two spec formats are read through
 * [com.example.ottomatic.domain.model.hubIdOf], which accepts either. Keeping the
 * parser choice out of here is what stops a sixth kind needing a `when` in two places
 * with no compiler to notice the second.
 */
fun smartHomeRefKeys(typeId: NodeTypeId): List<ConfigKey> =
    smartHomeRefFields(typeId).map { it.key }

/**
 * The same fields, unreduced.
 *
 * The validator needs more than the key: it has to know whether a **blank** value is an
 * unfinished field or a real answer, which only the declaration says. Returning the field
 * rather than adding a second parallel lookup keeps that a property of the one thing that
 * knows it.
 */
fun smartHomeRefFields(typeId: NodeTypeId): List<ConfigField<*>> =
    ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
        .filter { (it.type as? ConfigFieldType.PICKER)?.kind in HUB_SCOPED_PICKERS }

/** Whether this field's blank is a real answer rather than an unfinished one. */
val ConfigField<*>.isOptionalPicker: Boolean
    get() = (type as? ConfigFieldType.PICKER)?.optional == true

/**
 * Every picker whose value names something on a hub.
 *
 * [PickerKind.HA_TRIGGER] is deliberately **not** one, and this is the list that says why it
 * matters: these are validated by parsing the value as a `HomeAssistantRef`, and a trigger is
 * stored as Home Assistant's own bare id instead. Including it would report every configured
 * trigger as a reference to a hub that has been removed.
 *
 * [PickerKind.MQTT_BROKER] **is** one, on the same test read the other way: its value is a
 * `HubRef`, which `hubIdOf` parses, so the one question this list is for — *is the hub inside
 * this still set up?* — has an answer. An MQTT topic field beside it is not here for
 * `HA_TRIGGER`'s exact reason: a topic is a bare string that names no broker.
 */
private val HUB_SCOPED_PICKERS = setOf(
    PickerKind.LIGHT_TARGET,
    PickerKind.LIGHT_SCENE,
    PickerKind.HA_ENTITY,
    PickerKind.HA_SERVICE,
    PickerKind.HA_HUB,
    PickerKind.MQTT_BROKER,
)

/** The config keys of [typeId] that hold an [com.example.ottomatic.domain.model.AiModelProfile] id. */
fun aiModelRefKeys(typeId: NodeTypeId): List<ConfigKey> =
    ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
        .filter { (it.type as? ConfigFieldType.PICKER)?.kind == PickerKind.AI_MODEL }
        .map { it.key }

/**
 * The config keys of [typeId] that hold a
 * [com.example.ottomatic.domain.model.ToolOverrides] list.
 *
 * One node has this today, and it is still derived rather than named: a second AI node
 * that adjusts its profile's tools should be covered by having the annotation, not by
 * being added to a list somewhere else.
 */
fun toolListKeys(typeId: NodeTypeId): List<ConfigKey> =
    ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
        .filter { it.type is ConfigFieldType.TOOL_LIST }
        .map { it.key }

/** The config keys of [typeId] that hold a [PhoneRef] spec. */
fun phoneRefKeys(typeId: NodeTypeId): List<ConfigKey> =
    ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
        .filter { it.type == ConfigFieldType.PHONE }
        .map { it.key }

/**
 * Whether [node] points at a contact, and so needs `READ_CONTACTS` to resolve it
 * when it runs.
 *
 * A **config-dependent** requirement, which is why it is derived here rather than
 * declared in the node's `NodeTypeDefinition`: the same `action.call` needs contacts
 * access or does not depending on what the user put in the field, and a static
 * permission list can only say "always". Declaring it statically would put an amber
 * card on every call node in the app, including the overwhelmingly common one
 * holding a number somebody typed — and a warning that is permanently on is one
 * people learn to scroll past, which is the same failure as a warning nobody can see.
 */
fun usesContacts(node: WorkflowNode): Boolean =
    phoneRefKeys(node.typeId).any { PhoneRef.parse(node.config[it].orEmpty()) is PhoneRef.Contact }
