package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.CalendarAvailability
import io.github.m1n1m1.easymatic.core.service.CalendarOp
import io.github.m1n1m1.easymatic.core.service.EventPatch
import io.github.m1n1m1.easymatic.core.service.EventUpdate
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.SeriesScope
import io.github.m1n1m1.easymatic.domain.model.CalendarEventRef
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.CalendarChanged
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.calendar_update`.
 *
 * [ref] is the `ref` field of an appointment `action.calendar_query`,
 * `trigger.calendar_event` or `value.calendar_next` produced — wire it in through an
 * `action.break`, which is how every struct field reaches a downstream node.
 *
 * **A blank field leaves that part of the appointment alone.** That is the price of the
 * change fields being shared with nothing, and on a node whose entire job is changing
 * things it is the reading somebody expects — the same promise `action.mail_update`'s
 * "Move to folder" makes. The consequence to know is that a description cannot be
 * *cleared* from here; there is nothing to distinguish "leave it" from "empty it" in one
 * text box.
 *
 * [scope] is deliberately **not** hidden behind [op]. It applies to both operations, and
 * hiding it under one would put this node's most destructive setting out of sight on
 * exactly the operation where it matters most.
 */
@Suppress("LongParameterList") // One property per form field; a config class is a flat declaration.
@Serializable
data class CalendarUpdateConfig(
    @Label("Appointment") @Wired val ref: String = "",
    @Label("What to do") val op: CalendarOp = CalendarOp.UPDATE,
    @Label("Applies to") val scope: SeriesScope = SeriesScope.THIS_OCCURRENCE,

    @VisibleWhen("op", "UPDATE")
    @Label("New title")
    @Hint("leave empty to keep it")
    @Wired
    val title: String = "",

    @VisibleWhen("op", "UPDATE")
    @Label("New start")
    @Hint("leave empty to keep it")
    @Wired
    val startsAt: DateTime = DateTime.EPOCH,

    @VisibleWhen("op", "UPDATE")
    @Label("New length in minutes")
    @Hint("-1 to keep it")
    val durationMinutes: Int = KEEP,

    @VisibleWhen("op", "UPDATE")
    @Label("New place")
    @Hint("leave empty to keep it")
    @Wired
    val location: String = "",

    @VisibleWhen("op", "UPDATE")
    @Label("New details")
    @Hint("leave empty to keep them")
    @Multiline @Wired
    val description: String = "",

    @VisibleWhen("op", "UPDATE") @Label("Change how it shows you") val changeAvailability: Boolean = false,

    @VisibleWhen("changeAvailability", "true") @Label("Shows you as")
    val availability: CalendarAvailability = CalendarAvailability.BUSY,
)

private const val KEEP = -1

/**
 * Action for `action.calendar_update`. Changes or deletes one appointment.
 *
 * **One node rather than two**, which is `action.mail_update`'s call for its reason: both
 * operations take the same appointment, produce the same receipt and pulse the single
 * `out`. What differs is one enum, which is what `@VisibleWhen` exists for.
 *
 * **The setting to understand is "Applies to", and its default is deliberate.** A
 * repeating appointment is one row in the calendar and many appointments in a diary, so
 * "delete this" is genuinely ambiguous — and the two readings are not equally recoverable.
 * Deleting one occurrence can be undone by hand in a minute; deleting the series takes
 * away every stand-up there will ever be, and looks exactly like the macro working until
 * the following week. So the default is **this appointment only**, and the receipt says
 * which one it did.
 *
 * A one-off appointment ignores the setting, because for it both answers are the same.
 * So does an occurrence that has already been edited away from its series — see
 * `CalendarWrites` for why that case has to be spotted rather than assumed.
 *
 * **One caveat worth knowing before writing a macro against it**: changing the *start* of
 * a whole repeating series is something the calendar provider handles in its own way when
 * that series already has edited occurrences in it, and the result is not something this
 * node can promise. Moving a single occurrence is exact.
 *
 * **Never throws.** An unparseable reference, an appointment deleted since the macro was
 * handed it, a read-only calendar, a provider that refused — all land on `state` with
 * `changed = false` and the reason in `error`, and `out` still pulses.
 */
class CalendarUpdateAction : Action<CalendarUpdateConfig, CalendarChanged> {

    override val definition = actionNode<CalendarUpdateConfig, CalendarChanged>(
        typeId = "action.calendar_update",
        displayName = "Update Calendar Event",
        description = "Changes or deletes an appointment, either one occurrence of it or the whole series",
        category = NodeCategory.CALENDAR,
        icon = NodeIcon.CALENDAR,
        output = dataOut<CalendarChanged>("state"),
        // Both, on `action.calendar_add`'s reasoning: the event row is read before
        // anything decides which of the write paths to take.
        permissions = listOf(READ_CALENDAR_PERMISSION, WRITE_CALENDAR_PERMISSION),
    )

    override suspend fun execute(
        input: CalendarUpdateConfig,
        context: ExecutionContext,
    ): NodeOutput<CalendarChanged> {
        val problem = when {
            input.ref.isBlank() -> "No appointment wired in"
            // Names what it read rather than only that it failed: a reference that arrived
            // through a variable or a text transform is exactly the case where seeing the
            // string is the whole diagnosis.
            CalendarEventRef.parse(input.ref) == null -> "Not an appointment reference: \"${input.ref.trim()}\""
            else -> null
        }
        if (problem != null) {
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(input.receipt(changed = false, error = problem))
        }

        val result = context.calendars.update(
            EventUpdate(
                ref = input.ref,
                op = input.op,
                scope = input.scope,
                patch = input.patch(),
            ),
        )
        if (!result.changed) {
            if (result.error.isBlank()) {
                // Reached, and there was nothing to do. `LightChanged`'s case: a macro that
                // tidies a calendar every evening should not file a warning on the evenings
                // it was already tidy.
                context.log("Nothing about that appointment changed")
            } else {
                context.log(result.error, LogLevel.WARN)
            }
        }
        return NodeOutput(input.receipt(result.changed, result.error))
    }

    private fun CalendarUpdateConfig.receipt(changed: Boolean, error: String) = CalendarChanged(
        ref = ref,
        op = op.name,
        scope = scope.name,
        changed = changed,
        error = error,
    )

    /**
     * The form's blanks, as the facade's nulls.
     *
     * The one place "empty means leave it alone" is turned into something the write side
     * can act on — [EventPatch] carries nulls precisely so *it* never has to guess, and
     * the guessing is done here, once, where the promise to the user was made.
     */
    private fun CalendarUpdateConfig.patch(): EventPatch = EventPatch(
        title = title.takeIf { it.isNotBlank() },
        description = description.takeIf { it.isNotBlank() },
        location = location.takeIf { it.isNotBlank() },
        startEpochMs = startsAt.takeIf { it != DateTime.EPOCH }?.epochMs,
        durationMinutes = durationMinutes.takeIf { it > 0 }?.toLong(),
        availability = availability.takeIf { changeAvailability },
    )

    private companion object {
        val READ_CALENDAR_PERMISSION = PermissionRequirement(
            manifestPermission = Permissions.READ_CALENDAR.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "calendar.read",
        )
        val WRITE_CALENDAR_PERMISSION = PermissionRequirement(
            manifestPermission = Permissions.WRITE_CALENDAR.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "calendar.write",
        )
    }
}
