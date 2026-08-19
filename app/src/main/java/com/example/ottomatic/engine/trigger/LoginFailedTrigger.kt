package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.derivedOut
import com.example.ottomatic.domain.model.items.LoginAttempt
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * How many consecutive failures it takes. One — the default — is every wrong
 * entry, which is what somebody placing this node almost always means.
 */
@Serializable
data class LoginFailedConfig(
    @Label("After failed attempts") val afterAttempts: Int = 1,
)

/**
 * `trigger.login_failed` — starts when somebody fails to unlock the phone.
 *
 * The **only** thing on Android that reports this is a device administrator holding
 * `watch-login`: there is no broadcast, the lock screen belongs to no app's window
 * tree, and this app's accessibility service subscribes to no accessibility events
 * on purpose. So the grant is heavier than any other trigger's and is declared as a
 * prerequisite rather than assumed — an ungranted one is a `WARNING` in the Problems
 * panel and blocks nothing, because it is a fact about the phone rather than the wiring.
 *
 * The threshold is `>=`, not `==`: "after three attempts" fires on the third and on
 * every one after it, which is what somebody arming a camera or an alert wants. Firing
 * exactly once per streak is an `action.if` on the `attempts` port.
 */
class LoginFailedTrigger : Trigger<LoginFailedConfig, LoginAttempt> {

    override val definition = triggerNode<LoginFailedConfig, LoginAttempt>(
        typeId = TYPE_ID.value,
        displayName = "Login Attempt Failed",
        description = "Starts when someone enters the wrong PIN, pattern or password",
        category = NodeCategory.DEVICE_STATE,
        icon = NodeIcon.LOCK,
        output = dataOut<LoginAttempt>("attempt", label = "Attempt"),
        extraOutputs = listOf(
            derivedOut<Int, LoginAttempt>("attempts", label = "Attempts") { it.attempts },
        ),
        permissions = listOf(LOGIN_WATCH),
    )

    override fun activate(
        config: LoginFailedConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<LoginAttempt>> = flow {
        // Node-addressed, because a wrong PIN is nearly always a cold start: the phone
        // has been locked, which is exactly when Android has had every reason to reclaim
        // the process, so the event was parked rather than delivered.
        host.busEventsFor(node.id)
            .filter { it.source == TriggerSource.SECURITY }
            .filter { it.attempts.coerceAtLeast(1) >= config.afterAttempts }
            .collect { bus ->
                if (bus.payload[TriggerBus.KEY_HELD] != null) {
                    host.report(node, "An unlock failed while the engine was starting; running now")
                }
                emit(
                    NodeOutput(
                        LoginAttempt(attempts = bus.attempts, timestamp = bus.timestamp),
                    ),
                )
            }
    }

    companion object {

        val TYPE_ID = NodeTypeId("trigger.login_failed")

        internal const val KEY_ATTEMPTS = "attempts"

        private val LOGIN_WATCH = PermissionRequirement(
            manifestPermission = null,
            type = PrerequisiteType.DEVICE_ADMIN,
            rationaleKey = "login.watch",
        )

        /**
         * The count the receiver read, or **-1** when it could not read one.
         *
         * The filter above raises that to 1 rather than passing it on, and the two
         * halves are deliberately different. A threshold has to be answered somehow,
         * and a trigger that silently never fires is the worst of the outcomes: the
         * default case goes on working, while an explicit "after three" stays quiet
         * rather than firing on a number nobody has. The item keeps the -1, because
         * what is on the port is a reading and not a guess.
         */
        private val TriggerEvent.attempts: Int get() = payload[KEY_ATTEMPTS]?.toIntOrNull() ?: -1
    }
}
