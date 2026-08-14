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
    ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
        .filter { (it.type as? ConfigFieldType.PICKER)?.kind in HUB_SCOPED_PICKERS }
        .map { it.key }

/** Every picker whose value names something on a hub. */
private val HUB_SCOPED_PICKERS = setOf(
    PickerKind.LIGHT_TARGET,
    PickerKind.LIGHT_SCENE,
    PickerKind.HA_ENTITY,
    PickerKind.HA_SERVICE,
    PickerKind.HA_HUB,
)

/** The config keys of [typeId] that hold an [com.example.ottomatic.domain.model.AiConnection] id. */
fun aiConnectionRefKeys(typeId: NodeTypeId): List<ConfigKey> =
    ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
        .filter { (it.type as? ConfigFieldType.PICKER)?.kind == PickerKind.AI_CONNECTION }
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
