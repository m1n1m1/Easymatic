package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.IntentTarget
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode

/**
 * Action for `action.send_intent`. Starts an activity in another app from an intent the user
 * describes — an action string, optionally a data URI, a MIME type, a category and typed extras.
 *
 * The general case of `action.launch_app` and `action.open_url`, which are two fixed intents with
 * everything but one field decided in advance. It is deliberately an escape hatch, in
 * `action.script`'s sense: what it can express is bounded by what the phone happens to answer
 * rather than by anything declared here, and the audience arrived with a recipe rather than a
 * question.
 *
 * **The bound `@IntentChoice` holds structurally, this one holds by declaration.** That
 * annotation argues its open action string is safe because it has no component or package field
 * and always goes through `startActivityForResult`. Half of that is kept here — there is a
 * package field but still no *component* field, so what runs is always something the recipient
 * published in an `<intent-filter>` and `PackageManager` chose. A class name would reach past
 * that into another app's internals, is exactly the opaque identifier this app refuses to let
 * anybody type, and would break silently the next time that app renamed a class.
 *
 * **There is no result.** `startActivityForResult` needs an Activity and the engine has none, so
 * there is no reply to be had — and it would be answering a question nobody asked, since the user
 * has just left for another app and the macro is not waiting for them. `SystemServices` is a
 * write-only facade for this reason, and this node does not break it.
 *
 * Declares [LAUNCH_OVERLAY_PERMISSION], which `action.broadcast_intent` does not — see
 * [IntentConfig] for why that asymmetry is what makes these two nodes rather than one.
 */
class SendIntentAction : Action<IntentConfig, Unit> {

    override val definition = effectNode<IntentConfig>(
        typeId = "action.send_intent",
        displayName = "Send Intent",
        description = "Starts an activity in another app with a custom intent action, data and extras",
        category = NodeCategory.APPS,
        icon = NodeIcon.INTENT,
        permissions = listOf(LAUNCH_OVERLAY_PERMISSION),
    )

    override suspend fun execute(input: IntentConfig, context: ExecutionContext): NodeOutput<Unit> =
        context.runIntent(input, IntentTarget.ACTIVITY)
}
