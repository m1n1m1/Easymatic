package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.MailOp
import io.github.m1n1m1.easymatic.core.service.MailUpdate
import io.github.m1n1m1.easymatic.domain.model.MailRef
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Suggested
import io.github.m1n1m1.easymatic.domain.model.config.SuggestionSource
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.MailFlagged
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.mail_update`.
 *
 * [ref] is the `ref` field of a message a trigger or a fetch produced — wire it in
 * through an `action.break`, which is how every trigger's struct field reaches a
 * downstream node.
 */
@Serializable
data class MailUpdateConfig(
    @Label("Message") @Wired val ref: String = "",
    @Label("What to do") val op: MailOp = MailOp.MARK_READ,
    // No `accountKey` to name: this node's account arrives inside the wired
    // reference rather than from a field, so the chooser asks which account
    // first. Blank is the honest answer to "which sibling holds it?" here.
    @Label("Move to folder")
    @Suggested(SuggestionSource.MAIL_FOLDER)
    @VisibleWhen("op", "MOVE")
    @Wired
    val targetFolder: String = "",
)

/**
 * Action for `action.mail_update`. Marks a message read or unread, moves it to
 * another folder, or deletes it.
 *
 * **One node rather than four**, which is the opposite call from the dialog family
 * and worth saying why. Those are four nodes because they differ in *exec
 * routing* — one has a confirmed/cancelled branch, another a choice. These four
 * take the same input, produce the same output and all pulse the single `out`;
 * what differs is one enum, which is exactly what `@VisibleWhen` and
 * `action.http`'s method dropdown exist for. Four palette rows for one idea would
 * be the wrong trade.
 *
 * This is also the node that makes `trigger.mail` idempotent in practice: a macro
 * that marks what it handled will not be handed it again by the next check.
 *
 * Never throws. An unparseable reference, a message that has since been moved, and
 * a server that refused are all `changed = false` plus a reason, and `out` still
 * pulses.
 */
class MailUpdateAction : Action<MailUpdateConfig, MailFlagged> {

    override val definition = actionNode<MailUpdateConfig, MailFlagged>(
        typeId = "action.mail_update",
        displayName = "Update Email",
        description = "Marks a message read or unread, moves it to another folder, or deletes it",
        category = NodeCategory.NETWORK,
        icon = NodeIcon.MAIL,
        output = dataOut<MailFlagged>("state"),
    )

    override suspend fun execute(input: MailUpdateConfig, context: ExecutionContext): NodeOutput<MailFlagged> {
        val parsed = MailRef.parse(input.ref)
        val problem = when {
            parsed == null && input.ref.isBlank() -> "No message wired in"
            // Names what it read rather than only that it failed: a ref that
            // arrived through a variable or a text transform is exactly the case
            // where seeing the string is what explains it.
            parsed == null -> "Not a message reference: \"${input.ref.trim()}\""
            input.op == MailOp.MOVE && input.targetFolder.isBlank() ->
                "No destination folder, so there is nowhere to move it to"
            else -> null
        }
        if (parsed == null || problem != null) {
            context.log(problem.orEmpty(), LogLevel.ERROR)
            return NodeOutput(MailFlagged(input.ref, input.op.name, changed = false, error = problem.orEmpty()))
        }
        val result = context.mail.update(
            MailUpdate(
                accountId = parsed.accountId,
                folder = parsed.folder,
                uid = parsed.uid,
                uidValidity = parsed.uidValidity,
                op = input.op,
                targetFolder = input.targetFolder,
            ),
        )
        if (!result.changed) context.log(result.error, LogLevel.WARN)
        return NodeOutput(MailFlagged(input.ref, input.op.name, result.changed, result.error))
    }
}
