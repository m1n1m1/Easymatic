package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.PhoneNumber
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.CallInitiated
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import io.github.m1n1m1.easymatic.engine.resolvePhone
import kotlinx.serialization.Serializable

/**
 * Config for `action.call`.
 *
 * `number` holds a [io.github.m1n1m1.easymatic.domain.model.PhoneRef] spec — a number
 * typed into the field, or a contact chosen from the address book and resolved when
 * the node runs. It stays `@Wired`, which is what makes "call whoever just texted
 * me" expressible; a wired value is resolved through the same rule.
 */
@Serializable
data class CallConfig(
    @Label("Number") @PhoneNumber @Wired val number: String = "",
)

/**
 * Action for `action.call`. Initiates a phone call via `ACTION_CALL`. Reports
 * [CallInitiated] on its `state` data port.
 *
 * The port carries the **resolved** number rather than the stored spec, so a
 * `contact:` reference never leaks onto a wire as an opaque string — what came out
 * of this node is the number it actually dialled.
 */
class CallAction : Action<CallConfig, CallInitiated> {

    override val definition = actionNode<CallConfig, CallInitiated>(
        typeId = "action.call",
        displayName = "Make Call",
        description = "Initiates a phone call to a number",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.BOLT,
        output = dataOut<CallInitiated>("state"),
        // Without these the action returned false and said nothing about why.
        // The overlay grant is the second half of that: an unattended macro
        // dialling from the background is refused by Android silently, which on a
        // "call for help" node is the worst possible way to fail.
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.CALL_PHONE.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "call.phone",
            ),
            LAUNCH_OVERLAY_PERMISSION,
        ),
    )

    override suspend fun execute(input: CallConfig, context: ExecutionContext): NodeOutput<CallInitiated> {
        val number = context.resolvePhone(input.number)
        if (number == null) {
            context.log("No number to call — nothing chosen, or the contact could not be read", LogLevel.ERROR)
            return NodeOutput(CallInitiated(number = "", initiated = false))
        }
        val initiated = context.reportLaunch(context.systemServices.call(number), number)
        return NodeOutput(CallInitiated(number = number, initiated = initiated))
    }
}
