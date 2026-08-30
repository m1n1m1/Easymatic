package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.capabilities.DeviceCapability
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.trigger.gesture.FingerprintGesture
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.fingerprint_gesture`.
 *
 * Nullable, so the form prepends the blank "Any" option: leaving it unset starts the
 * macro on any of the four swipes, which is the shape `trigger.device_orientation`'s
 * filter already takes.
 */
@Serializable
data class FingerprintGestureConfig(
    @Label("Gesture") val gesture: FingerprintGesture? = null,
)

/**
 * Trigger for `trigger.fingerprint_gesture`. Fires when a finger is swiped across the
 * fingerprint reader.
 *
 * Needs the app's accessibility service to be switched on in Settings — as with key
 * events, there is no other API — which is why it declares an
 * [PrerequisiteType.ACCESSIBILITY_SERVICE] prerequisite.
 *
 * **It also declares [DeviceCapability.FINGERPRINT_GESTURES], and that is the
 * unusual part.** Android only reports swipes from a physical, usually rear-mounted
 * reader whose driver tracks them; in-display optical and ultrasonic sensors
 * generally report nothing at all, and `FEATURE_FINGERPRINT` does not tell the two
 * apart. So on most modern phones this node is inert no matter what the user grants —
 * and an inert trigger is indistinguishable from one that is simply waiting. The
 * capability is what makes the difference visible, in the Problems panel, without
 * anyone having to open the node. It is a warning and blocks nothing: the same macro
 * is correct on a phone that can run it.
 *
 * **Unlocking is untouched.** The platform withholds gestures while the sensor is
 * authenticating, so nothing here can see or interfere with an unlock — unlike the
 * volume path, this needed no decision, only a note.
 *
 * There is deliberately **no value node** beside it. A swipe is an event with no
 * resting value, like a shake, a tap or a volume press, none of which have one:
 * there is no "which way am I swiping right now".
 *
 * Produces a typed [SystemState] item on the `state` data port, whose `event` is the
 * direction swiped.
 */
class FingerprintGestureTrigger : Trigger<FingerprintGestureConfig, SystemState> {

    override val definition = triggerNode<FingerprintGestureConfig, SystemState>(
        typeId = TYPE_ID.value,
        displayName = "Fingerprint Gesture",
        description = "Starts when you swipe across the fingerprint reader. Needs accessibility " +
            "access, and only works on phones whose reader reports swipes; unlocking is unaffected",
        category = NodeCategory.SENSORS,
        icon = NodeIcon.FINGERPRINT,
        output = dataOut<SystemState>("state", label = "State"),
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = null,
                type = PrerequisiteType.ACCESSIBILITY_SERVICE,
                rationaleKey = "accessibility.fingerprint",
            ),
        ),
        capabilities = listOf(DeviceCapability.FINGERPRINT_GESTURES),
    )

    override fun activate(
        config: FingerprintGestureConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = host.busEvents()
        .filter { it.source == TriggerSource.HARDWARE }
        .filter { it.payload[KEY_TRIGGER_TYPE] == TRIGGER_TYPE }
        .mapNotNull { FingerprintGesture.fromPayload(it.payload[KEY_GESTURE]) }
        // Unlike the key path there is no history to keep, so the filter is a plain
        // comparison and the flow needs no per-collection state.
        .filter { config.gesture == null || config.gesture == it }
        .map { gesture ->
            NodeOutput(
                SystemState(
                    event = gesture.payloadValue,
                    timestamp = DateTime(System.currentTimeMillis()),
                ),
            )
        }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.fingerprint_gesture")

        /**
         * Must match the payload emitted by `EasymaticAccessibilityService`.
         *
         * Duplicated rather than shared because that class is in `data`, which
         * `engine` may not import and which may not import `engine` — the same
         * necessity `trigger.volume_button` lives with.
         * `FingerprintGestureRegistryTest` pins the two together.
         */
        const val TRIGGER_TYPE = "fingerprint"

        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_GESTURE = "gesture"
    }
}
