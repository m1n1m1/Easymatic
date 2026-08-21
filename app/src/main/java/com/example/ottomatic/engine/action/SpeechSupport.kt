package com.example.ottomatic.engine.action

import com.example.ottomatic.core.capabilities.DeviceCapability
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.ListenOutcome
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ExecutionRoute

/**
 * What the voice nodes share: which capability each half needs, how a listening attempt
 * becomes a branch, and the one limit the platform enforces silently.
 *
 * `PromptSupport`'s arrangement and its reason: the interesting decisions here belong to the
 * *family*, and an attempt that heard nothing must take the same branch on every node or
 * "nobody answered" would mean something different in each place it appears.
 */

/** The DATA output port on `action.listen` carrying what was heard. */
internal val LISTEN_TEXT_OUT = PortName("text")

/**
 * A text-to-speech engine, declared by the two speaking actions.
 *
 * `value.speaking` deliberately goes without, on `value.nfc`'s argument: a node whose entire
 * job is to answer whether something is speaking must never be badged for the phone having no
 * voice. False is the correct answer there, and it is the actions that should wear the
 * warning.
 */
internal val NEEDS_VOICE = listOf(DeviceCapability.SPEECH_SYNTHESIS)

/** Speech recognition, declared by `action.listen` alone. */
internal val NEEDS_EARS = listOf(DeviceCapability.SPEECH_RECOGNITION)

/**
 * The branch [outcome] takes, logging what happened on the way.
 *
 * Three endings and three routes, and the split that carries the most weight is the one
 * *inside* the cancelled branch. Nobody speaking is logged at INFO — it is the ordinary
 * outcome of a listening node and warning about it would fill the console every time somebody
 * walked away — while a recognizer that refused is logged at WARN, because unlike silence it
 * is something the user can fix.
 *
 * Both still take `nothing`, and deliberately: to the *graph* they are the same event, which
 * is "carry on without an answer". Splitting them into two exec ports would put a branch on
 * every card for a case almost no macro wants to handle separately, which is the argument
 * `PromptSupport.routeOf` makes for routing [com.example.ottomatic.core.service.PromptAnswer]
 * `.Unavailable` to `cancelled`.
 *
 * Nothing here halts the macro. Deciding what an unheard question means is an `action.if`
 * downstream, which is visible on the canvas.
 */
internal fun ExecutionContext.listenRouteOf(outcome: ListenOutcome): ExecutionRoute = when {
    outcome.heard -> {
        log("Heard '${outcome.text}'")
        ExecutionRoute.HEARD
    }

    outcome.timedOut -> {
        log("Nobody finished speaking in time")
        ExecutionRoute.TIMED_OUT
    }

    outcome.error.isNotBlank() -> {
        log("Could not listen: ${outcome.error}. Taking the nothing branch.", LogLevel.WARN)
        ExecutionRoute.NOTHING_HEARD
    }

    else -> {
        log("Nothing was said")
        ExecutionRoute.NOTHING_HEARD
    }
}
