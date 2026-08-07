package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NfcTagId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.NfcScan
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/** Blank [tagId] means any tag, the way a blank SSID means any network. */
@Serializable
data class NfcTagConfig(
    @Label("Tag") @Picker(PickerKind.NFC_TAG) val tagId: String = "",
)

/**
 * `trigger.nfc` — starts when an NFC tag is held to the phone.
 *
 * Matches on the tag's **hardware id**, which is burned in at the factory and
 * cannot be changed. That is what makes this work with any tag straight out of the
 * packet — blank ones, read-only ones, ones already written for something else —
 * with nothing to write and nothing to configure on the tag itself.
 *
 * The name comes from the tag library rather than the config, resolved at the
 * moment the tap arrives. So renaming a tag is visible to macros that are already
 * armed, and deleting one costs this trigger its [NfcScan.tagName] and nothing
 * else — the tap still matches. That is why a tag reference gets no validator
 * warning where a missing place or macro does.
 */
class NfcTagTrigger : Trigger<NfcTagConfig, NfcScan> {

    override val definition = triggerNode<NfcTagConfig, NfcScan>(
        typeId = TYPE_ID.value,
        displayName = "NFC Tag",
        description = "Starts when an NFC tag is held to the phone. Only works while the " +
            "screen is on and unlocked",
        category = NodeCategory.CONNECTIVITY,
        icon = NodeIcon.NFC,
        output = dataOut<NfcScan>("tag", label = "Tag"),
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = null,
                type = PrerequisiteType.NFC,
                rationaleKey = "nfc.radio",
            ),
        ),
    )

    override fun activate(
        config: NfcTagConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<NfcScan>> = flow {
        report(config, node, host)
        // Node-addressed, because a tap is nearly always a cold start: the tag is
        // what launched the process, so the event was parked rather than delivered
        // and only this collector can drain it.
        host.busEventsFor(node.id)
            .filter { it.source == TriggerSource.NFC }
            .collect { bus ->
                val tagId = bus.payload[KEY_TAG_ID].orEmpty()
                if (!NfcTagId.matches(config.tagId, tagId)) return@collect
                if (bus.payload[TriggerBus.KEY_HELD] != null) {
                    host.report(node, "A tag was tapped while the engine was starting; running now")
                }
                emit(
                    NodeOutput(
                        NfcScan(
                            tagId = tagId,
                            tagName = host.nfcTag(tagId)?.name.orEmpty(),
                            text = bus.payload[KEY_TEXT].orEmpty(),
                            timestamp = bus.timestamp,
                        ),
                    ),
                )
            }
    }

    /**
     * What this trigger is armed for, and the two ways it can be armed and still be
     * incapable of firing.
     *
     * Neither is reachable from anywhere else. The Permissions screen answers
     * "switched off" but deliberately calls a phone with no chip *satisfied* —
     * there is nothing there to grant, and a row that can never go green is worse
     * than no row. And nothing at all knows about the per-app tag-intent switch.
     * Both look exactly like a macro that is simply waiting.
     */
    private fun report(config: NfcTagConfig, node: WorkflowNode, host: TriggerHost) {
        val watching = if (config.tagId.isBlank()) {
            "Watching for any NFC tag"
        } else {
            val name = host.nfcTag(config.tagId)?.name
            "Watching for '${name ?: NfcTagId.display(config.tagId)}'"
        }
        host.report(node, watching)
        when (host.nfcStatus()) {
            NfcStatus.NO_HARDWARE -> host.report(
                node,
                "This phone has no NFC, so this trigger can never fire",
                LogLevel.WARN,
            )
            NfcStatus.TAG_INTENTS_BLOCKED -> host.report(
                node,
                "Tag scanning is switched off for Ottomatic in Android's NFC settings, so a " +
                    "tap will never reach this trigger",
                LogLevel.WARN,
            )
            NfcStatus.OK, NfcStatus.UNKNOWN -> Unit
        }
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.nfc")

        const val KEY_TAG_ID = "tagId"
        const val KEY_TEXT = "text"
    }
}

/**
 * The two ways NFC can be unusable that no permission check reports.
 *
 * [UNKNOWN] is the default a host with no platform behind it answers, and it is
 * deliberately silent rather than pessimistic: a test double or a preview knowing
 * nothing about the radio must not make every armed trigger complain.
 */
enum class NfcStatus { OK, NO_HARDWARE, TAG_INTENTS_BLOCKED, UNKNOWN }
