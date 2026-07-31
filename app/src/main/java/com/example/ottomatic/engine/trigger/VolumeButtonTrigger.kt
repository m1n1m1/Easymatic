package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import com.example.ottomatic.engine.trigger.keys.KeyGesture
import com.example.ottomatic.engine.trigger.keys.KeyGestureDetector
import com.example.ottomatic.engine.trigger.keys.KeyLongPressDetector
import com.example.ottomatic.engine.trigger.keys.KeyPress
import com.example.ottomatic.engine.trigger.keys.KeyPressDetector
import com.example.ottomatic.engine.trigger.keys.KeySequenceDetector
import com.example.ottomatic.engine.trigger.keys.VolumeKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

/** Config for `trigger.volume_button`. */
@Serializable
data class VolumeButtonConfig(
    @Label("Button") val key: VolumeKey = VolumeKey.VOLUME_DOWN,
    @Label("Gesture") val gesture: KeyGesture = KeyGesture.SEQUENCE,
    @Label("Hold for at least (ms)")
    @VisibleWhen("gesture", "LONG_PRESS")
    val holdMs: Long = KeyLongPressDetector.DEFAULT_HOLD_MS,
    @Label("Number of presses")
    @VisibleWhen("gesture", "SEQUENCE")
    val presses: Int = KeySequenceDetector.DEFAULT_PRESSES,
    @Label("Within (ms)")
    @VisibleWhen("gesture", "SEQUENCE")
    val windowMs: Long = KeySequenceDetector.DEFAULT_WINDOW_MS,
)

/**
 * Trigger for `trigger.volume_button`. Fires when a volume button is pressed,
 * held, or pressed several times in a row.
 *
 * Needs the app's accessibility service to be switched on in Settings — there is
 * no other way for an app to see key events. Until then the service is never
 * bound and this trigger is silently inert, which is why the node declares an
 * [PrerequisiteType.ACCESSIBILITY_SERVICE] prerequisite: the editor turns that
 * into a notice with a link to the right settings page.
 *
 * **The volume keys keep working.** The service never consumes an event, so
 * pressing volume down still turns the volume down. Undoing that is an
 * `action.volume` in the graph, where it is visible.
 *
 * [KeyGesture.SEQUENCE] is the default because a single volume press is
 * something the user does constantly for its ordinary purpose; three in a row is
 * a deliberate signal.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class VolumeButtonTrigger : Trigger<VolumeButtonConfig, SystemState> {

    override val definition = triggerNode<VolumeButtonConfig, SystemState>(
        typeId = TYPE_ID.value,
        displayName = "Volume Button",
        description = "Starts when a volume button is pressed, held, or pressed several " +
            "times. Needs accessibility access; the buttons keep working normally",
        category = NodeCategory.DEVICE_STATE,
        icon = NodeIcon.VOLUME,
        output = dataOut<SystemState>("state", label = "State"),
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = null,
                type = PrerequisiteType.ACCESSIBILITY_SERVICE,
                rationaleKey = "accessibility.keys",
            ),
        ),
    )

    override fun activate(
        config: VolumeButtonConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = flow {
        // Per collection: every one of these carries press history.
        val detector = detectorFor(config)
        host.busEvents()
            .filter { it.source == TriggerSource.HARDWARE }
            .filter { it.payload[KEY_TRIGGER_TYPE] == TRIGGER_TYPE }
            .mapNotNull { it.toKeyPress() }
            // Every volume event is fed in, not just the configured key: the
            // sequence detector has to see the *other* key to know a run was
            // interrupted.
            .filter { detector.update(it) }
            .map { press ->
                NodeOutput(
                    SystemState(
                        event = config.gesture.payloadValue,
                        detail = press.key.payloadValue,
                        timestamp = com.example.ottomatic.domain.model.schema.DateTime(
                            System.currentTimeMillis(),
                        ),
                    ),
                )
            }
            .collect { emit(it) }
    }

    private fun detectorFor(config: VolumeButtonConfig): KeyGestureDetector = when (config.gesture) {
        KeyGesture.PRESS -> KeyPressDetector(config.key)
        KeyGesture.LONG_PRESS -> KeyLongPressDetector(config.key, config.holdMs.coerceAtLeast(0L))
        KeyGesture.SEQUENCE -> KeySequenceDetector(
            key = config.key,
            presses = config.presses.coerceAtLeast(1),
            windowMs = config.windowMs.coerceAtLeast(1L),
        )
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.volume_button")

        /** Must match the payload emitted by `OttomaticAccessibilityService`. */
        const val TRIGGER_TYPE = "key"

        const val KEY_KEY = "key"
        const val KEY_ACTION = "action"
        const val KEY_REPEAT = "repeat"
        const val KEY_EVENT_TIME = "eventTime"

        const val VALUE_VOLUME_UP = "volume_up"
        const val VALUE_VOLUME_DOWN = "volume_down"
        const val VALUE_DOWN = "down"
    }
}

/** Parses a key bus event, or null when it is malformed. */
internal fun TriggerEvent.toKeyPress(): KeyPress? {
    val key = when (payload[VolumeButtonTrigger.KEY_KEY]) {
        VolumeButtonTrigger.VALUE_VOLUME_UP -> VolumeKey.VOLUME_UP
        VolumeButtonTrigger.VALUE_VOLUME_DOWN -> VolumeKey.VOLUME_DOWN
        else -> return null
    }
    return KeyPress(
        key = key,
        down = payload[VolumeButtonTrigger.KEY_ACTION] == VolumeButtonTrigger.VALUE_DOWN,
        repeatCount = payload[VolumeButtonTrigger.KEY_REPEAT]?.toIntOrNull() ?: 0,
        eventTimeMs = payload[VolumeButtonTrigger.KEY_EVENT_TIME]?.toLongOrNull() ?: firedAtEpochMs,
    )
}
