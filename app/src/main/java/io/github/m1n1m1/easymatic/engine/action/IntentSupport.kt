package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.IntentTarget
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.IntentPlan
import io.github.m1n1m1.easymatic.domain.model.IntentSpec
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput

/**
 * What the two intent nodes do, written once.
 *
 * On `ForegroundLaunch`'s reasoning: the interesting decision here is a decision about the
 * *family*. An intent that went nowhere must be called the same thing on both, or "it didn't
 * work" would mean two things depending on which node was asked. What the nodes themselves
 * disagree about is one enum member and one permission — see [IntentConfig] for why that is
 * enough to make them two nodes rather than one.
 */

/**
 * Sends what [config] describes as [target], and says in the run log what became of it.
 *
 * Pulses `out` in every case, including a refusal — nothing here halts a macro, which is
 * `action.open_url`'s stance and for its reason. The three ways this can go are three different
 * log lines because they send the user to three different places: fix the config, fix the phone,
 * or fix nothing because it probably worked.
 */
internal fun ExecutionContext.runIntent(config: IntentConfig, target: IntentTarget): NodeOutput<Unit> {
    val plan = IntentSpec.plan(
        target = target,
        action = config.action,
        packageName = config.packageName,
        data = config.dataUri,
        mimeType = config.mimeType,
        category = config.category,
        extras = config.extras,
    )
    when (plan) {
        is IntentPlan.Refused -> log(plan.reason, LogLevel.ERROR)

        is IntentPlan.Ready -> {
            // Before the send rather than after, so an extra that was dropped sits above the
            // line saying the intent went out. An extra silently missing from a launch that
            // succeeded is the whole failure typed extras exist to prevent, and the ordering is
            // what makes the two lines read as one story.
            plan.notes.forEach { log(it, LogLevel.WARN) }
            reportLaunch(systemServices.sendIntent(plan.recipe), config.action.trim())
        }
    }
    return NodeOutput(Unit)
}
