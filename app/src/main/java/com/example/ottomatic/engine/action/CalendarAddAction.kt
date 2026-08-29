package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.CalendarAvailability
import com.example.ottomatic.core.service.EventDraft
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.CalendarWritten
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.calendar_add`.
 *
 * **A length rather than an end time**, and that is not a stylistic call. Two absolute
 * fields make "ends before it starts" representable with nothing anywhere to detect it,
 * "a one-hour meeting" is what people mean, and an all-day appointment has to be
 * expressed in whole days — which a second timestamp makes needlessly fiddly.
 * `ScheduleFire.elapsedMs`' rule: a duration is not a
 * [DateTime][com.example.ottomatic.domain.model.schema.DateTime].
 *
 * Almost everything is `@Wired`, because the macro this node exists for builds its
 * appointment out of something it just read — a message, an HTTP response, a model's
 * answer.
 */
@Suppress("LongParameterList") // One property per form field; a config class is a flat declaration.
@Serializable
data class CalendarAddConfig(
    @Label("Calendar") @Picker(PickerKind.CALENDAR) val calendar: String = "",
    @Label("Title") @Wired val title: String = "",
    @Label("Starts at") @Wired val startsAt: DateTime = DateTime.EPOCH,
    @Label("How long (minutes)") @Wired val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
    @Label("All day") val allDay: Boolean = false,
    @Label("Where") @Wired val location: String = "",
    @Label("Details") @Multiline @Wired val description: String = "",
    @Label("Shows you as") val availability: CalendarAvailability = CalendarAvailability.BUSY,
    @Label("Remind you")
    @Hint("minutes before, -1 for no reminder")
    val reminderMinutes: Int = NO_REMINDER,
)

private const val DEFAULT_DURATION_MINUTES = 60
private const val NO_REMINDER = -1

/**
 * Action for `action.calendar_add`. Puts an appointment in a calendar.
 *
 * `action.send_mail`'s node, for calendars: the family's one *creating* operation, kept
 * separate from `action.calendar_update` for the reason the light nodes are three rather
 * than one — the **declared ports differ**. This takes no appointment reference and hands
 * one back; that one takes a reference and answers what it did to it.
 *
 * **A blank start time means "now"**, which is the only reading that makes the commonest
 * unattended macro work: "when I get a message from the boss, put an hour in my diary" is
 * about the moment it fires, and there is nothing else a blank field could sensibly mean
 * on a node whose whole subject is time.
 *
 * **Never throws.** No calendar chosen, a calendar that is read-only or has been removed,
 * a title left blank, a provider that refused — all land on `state` with
 * `created = false` and the reason in `error`, and `out` still pulses.
 * `action.light_control`'s contract, for its reason.
 */
class CalendarAddAction : Action<CalendarAddConfig, CalendarWritten> {

    override val definition = actionNode<CalendarAddConfig, CalendarWritten>(
        typeId = "action.calendar_add",
        displayName = "Add Calendar Event",
        description = "Creates an appointment in one of the calendars on this phone",
        category = NodeCategory.CALENDAR,
        icon = NodeIcon.CALENDAR,
        output = dataOut<CalendarWritten>("state"),
        // Both, and not only the write. The provider is read before anything is inserted
        // — to resolve the calendar and to check it is not a subscription — so a
        // write-only grant would fail at the read and report something about writing.
        permissions = listOf(READ_CALENDAR_PERMISSION, WRITE_CALENDAR_PERMISSION),
    )

    override suspend fun execute(
        input: CalendarAddConfig,
        context: ExecutionContext,
    ): NodeOutput<CalendarWritten> {
        val start = input.startsAt.takeIf { it != DateTime.EPOCH }?.epochMs ?: System.currentTimeMillis()
        val result = context.calendars.add(
            EventDraft(
                calendarSpec = input.calendar,
                title = input.title,
                description = input.description,
                location = input.location,
                startEpochMs = start,
                durationMinutes = input.durationMinutes.toLong(),
                allDay = input.allDay,
                availability = input.availability,
                reminderMinutes = input.reminderMinutes,
            ),
        )
        if (result.error.isNotEmpty()) context.log(result.error, LogLevel.ERROR)
        return NodeOutput(
            CalendarWritten(
                ref = result.ref,
                title = input.title,
                created = result.changed,
                error = result.error,
            ),
        )
    }

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
