package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.IntentTarget
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode

/**
 * Action for `action.broadcast_intent`. Sends a broadcast to whatever is listening for it.
 *
 * `action.send_intent`'s sibling and the half most recipes actually want: an app that exposes
 * anything to automation usually exposes it as a receiver, not as an activity. Everything a user
 * can configure is shared — see [IntentConfig], which also carries the argument for why the
 * mechanism is two palette entries rather than a field on one.
 *
 * **It declares no permissions, and the absence is load-bearing rather than an oversight.**
 * [LAUNCH_OVERLAY_PERMISSION] exists because Android 10 forbids a background app from starting an
 * *Activity*; a broadcast is under no such rule and works from the engine's service with nothing
 * granted at all. Declaring it here for symmetry with `action.send_intent` would put an amber card
 * on this node's config form and a warning in the Problems panel for a grant that would change
 * nothing — badging a node for working, which is the mistake `value.nfc` records in the other
 * direction.
 *
 * **What it cannot tell you is whether anybody heard.** `sendBroadcast` returns `void` and
 * succeeds whether or not a single receiver exists, so `LaunchOutcome.NoReceiver` is a suspicion
 * assembled beforehand and reported at WARN rather than ERROR — the node did what it was asked.
 * The two ways that suspicion is wrong are on that member. The related trap is Android 8's, and
 * it is reported separately by `IntentSpec`: an implicit broadcast no longer reaches a receiver
 * declared in another app's manifest, so naming an app in the App field is usually necessary and
 * leaving it blank earns its own line in the console.
 */
class BroadcastIntentAction : Action<IntentConfig, Unit> {

    override val definition = effectNode<IntentConfig>(
        typeId = "action.broadcast_intent",
        displayName = "Broadcast Intent",
        description = "Sends a broadcast intent to another app, with a custom action and extras",
        category = NodeCategory.APPS,
        icon = NodeIcon.BROADCAST,
    )

    override suspend fun execute(input: IntentConfig, context: ExecutionContext): NodeOutput<Unit> =
        context.runIntent(input, IntentTarget.BROADCAST)
}
