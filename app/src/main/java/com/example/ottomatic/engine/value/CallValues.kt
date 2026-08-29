package com.example.ottomatic.engine.value

import com.example.ottomatic.core.service.CallStatus
import com.example.ottomatic.core.trigger.CallPayload
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.CallEvent
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.trigger.CALL_PERMISSIONS
import com.example.ottomatic.engine.valueNode

/**
 * The two pull-side readings of whether somebody is on a call.
 *
 * **The value half of `trigger.call_state`**, which the pairing rule asks for and which
 * was the one thing missing from the call nodes: a trigger answers "tell me when this
 * changes", and without a value beside it "if I am on a call, do not read my messages
 * aloud" needs a second macro whose only job is keeping a variable in step with the
 * first. That variable is wrong the moment either macro is disabled.
 *
 * **They clear the pull-side bar `value.ha_state`'s way.** The read is a lookup into
 * `CallSessions`, a map two callbacks keep warm — cheap, repeatable, and with nothing to
 * fail. Under it sits `TelecomManager.isInCall`, one synchronous binder call into a
 * service that is always running, for the case a call was already going on when this
 * process started and there was no event to see.
 *
 * **Both declare the call grants**, which is legal for a value since 2026-08 and right
 * here for `value.media_playing`'s reason rather than `value.nfc`'s: neither grant is the
 * *subject* of the question, both are only what make the read possible. The subject is
 * whether a call is happening, and a phone that will not say is not a phone with no call
 * on it.
 */

/**
 * `value.call_active` — whether a call is going on right now.
 *
 * Answers **false when there is definitely no call and null when nothing can be known**,
 * and the distinction is the whole reason a comparison over this can be trusted. Null
 * contributes no item, so the consumer falls back and the comparison fails closed; false
 * is a real answer that gets compared. Collapsing the two would make a denied permission
 * look exactly like a quiet phone, and "if I am not on a call, play this out loud" would
 * fire in the middle of one.
 *
 * A call that is merely **ringing is not active**. Somebody has not picked it up, and
 * "am I on a call?" is answered no while the phone is still ringing on the table. The
 * ringing case has a trigger of its own and needs no value: it lasts seconds, so nothing
 * would ever read it in time.
 */
class CallActiveValue : ValueNode<NoConfig, Boolean> {

    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.call_active",
        displayName = "On a call",
        description = "Whether a call is in progress right now, on the phone or in " +
            "Teams, WhatsApp, Discord or another calling app",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.CALL,
        output = dataOut("active", label = "On a call"),
        permissions = CALL_PERMISSIONS,
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): Boolean? =
        context.deviceState.currentCall()?.active
}

/**
 * `value.current_call` — the call going on right now, with who it is with.
 *
 * **The struct behind [CallActiveValue]'s boolean**, the pairing `value.media_playing` and
 * `value.now_playing` already make, and what lets a macro say something about the call
 * rather than only react to one: "when I plug the car in during a call, read me the
 * caller's name" is one wire from here.
 *
 * **A ringing call answers too**, with [CallEvent.state] reading `"ringing"`. Restricting
 * this to connected calls would leave the most useful moment unreadable — deciding what
 * to do about a call that has not been picked up yet is exactly when knowing who it is
 * matters.
 *
 * [CallEvent.answered] and [CallEvent.durationSeconds] are always false and zero here.
 * Neither has an answer until a call is over, and a call that is over is not one this can
 * report; `trigger.call_ended` is where both live.
 *
 * Null when there is no call and null when the read failed, deliberately not told apart:
 * there is no caller, no app and no state to report in either case, so the distinction
 * would let this give no answer it cannot already give. [CallActiveValue] is where the
 * distinction is load-bearing, and it keeps it.
 */
class CurrentCallValue : ValueNode<NoConfig, CallEvent> {

    override val definition = valueNode<NoConfig, CallEvent>(
        typeId = "value.current_call",
        displayName = "Current call",
        description = "The call in progress, with who it is with and which app it is in",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.CALL,
        output = dataOut("call", label = "Call"),
        permissions = CALL_PERMISSIONS,
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): CallEvent? =
        context.deviceState.currentCall()?.takeIf { it.active || it.ringing }?.toItem()
}

/**
 * The one place the facade's reading becomes the graph's struct.
 *
 * [com.example.ottomatic.engine.value.toItem]'s arrangement for `NowPlaying`, and it
 * exists for the boundary rather than for tidiness: `core` may not import `domain`, so
 * [CallStatus] and [CallEvent] cannot be one type however alike they look.
 */
private fun CallStatus.toItem(): CallEvent = CallEvent(
    state = if (active) CallPayload.STATE_ACTIVE else CallPayload.STATE_RINGING,
    caller = caller,
    appName = appName,
    packageName = packageName,
    incoming = incoming,
    video = video,
    timestamp = DateTime.now(),
)
