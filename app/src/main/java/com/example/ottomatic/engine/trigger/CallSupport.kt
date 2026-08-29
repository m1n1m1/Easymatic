package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.trigger.CallPayload
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.domain.model.items.CallEvent

/**
 * What the four call nodes need in common.
 *
 * A call is unusual in reaching the phone down **two** roads, and both of them are gated:
 * the cellular radio behind `READ_PHONE_STATE`, and every other calling app behind
 * notification access. Declaring both on every call node is what makes the degradation
 * legible — with notification access off, cellular calls still work and the Problems
 * panel says exactly which half is missing, rather than leaving somebody to wonder why
 * their Teams macro never fires.
 *
 * Neither grant blocks anything. A node with one of them does most of its job, which is
 * the case a `WARNING` was invented for.
 */
internal val CALL_PERMISSIONS = listOf(
    PermissionRequirement(
        manifestPermission = Permissions.READ_PHONE_STATE.manifest,
        type = PrerequisiteType.RUNTIME,
        rationaleKey = "call.state",
    ),
    PermissionRequirement(
        manifestPermission = null,
        type = PrerequisiteType.NOTIFICATION_LISTENER,
        rationaleKey = "call.notification",
    ),
)

/**
 * This bus event as the item the call nodes emit.
 *
 * The keys come from [CallPayload] in `core`, which the `data/` side fills using the same
 * constants — so unlike the fingerprint and volume-key contracts, which are hand-copied
 * across the `data`/`engine` line and kept honest by a test, this one cannot drift.
 */
internal fun TriggerEvent.toCallEvent(): CallEvent = CallEvent(
    state = payload[CallPayload.KEY_STATE].orEmpty(),
    caller = payload[CallPayload.KEY_CALLER].orEmpty(),
    appName = payload[CallPayload.KEY_APP_NAME].orEmpty(),
    packageName = payload[CallPayload.KEY_PACKAGE].orEmpty(),
    // Absent reads as outgoing rather than incoming, which is the safe direction: a
    // filter for "incoming calls" should miss a malformed event, not match it.
    incoming = payload[CallPayload.KEY_INCOMING].toBoolean(),
    video = payload[CallPayload.KEY_VIDEO].toBoolean(),
    answered = payload[CallPayload.KEY_ANSWERED].toBoolean(),
    durationSeconds = payload[CallPayload.KEY_DURATION_SECONDS]?.toIntOrNull() ?: 0,
    timestamp = timestamp,
)
