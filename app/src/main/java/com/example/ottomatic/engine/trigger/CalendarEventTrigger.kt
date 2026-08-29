package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.CalendarLimits
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.CalendarEvent
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.action.toItem
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.calendar_event`.
 *
 * [leadMinutes] is hidden in the reminder mode because there is nothing for it to do
 * there: the moment comes from the appointment's own reminder, which is the whole point of
 * that mode. A field that silently did nothing would be worse than one that is not there.
 */
@Serializable
data class CalendarEventTriggerConfig(
    @Label("Fire when") val fireWhen: CalendarWhen = CalendarWhen.STARTS,

    @VisibleWhen("fireWhen", "STARTS", "ENDS")
    @Label("Minutes before")
    @Hint("negative fires afterwards")
    val leadMinutes: Int = 0,

    @Label("Calendar")
    @Picker(PickerKind.CALENDAR_FILTER, optional = true)
    val calendar: String = "",

    @Label("Title contains") val titleContains: String = "",
) {
    internal fun toSpec() = CalendarWatchSpec(
        mode = fireWhen,
        calendarSpec = calendar,
        leadMinutes = leadMinutes,
        titleContains = titleContains,
    )
}

/**
 * Trigger for `trigger.calendar_event`. Fires at a moment belonging to one appointment.
 *
 * **Three modes and one mechanism**, which is the thing to understand before changing
 * anything here. It is tempting to service the reminder mode from
 * `CalendarContract.ACTION_EVENT_REMINDER` — the constant exists, and the provider really
 * does broadcast it. It cannot be used: the broadcast is *implicit*, and Android 8 stopped
 * delivering implicit broadcasts to manifest receivers except for an exemption list this
 * is not on. A runtime-registered receiver would work only while the process happened to
 * be alive, which is precisely the moment a trigger must not depend on. So all three modes
 * plan their own exact alarm and differ only in where the moment comes from — see
 * [planNext]. The reminder mode reads it off the appointment's own reminder row, which as
 * a bonus works on phones whose OEM calendar has replaced the provider's alert path.
 *
 * The loop is `ScheduleTrigger.alarmFlow`'s, with two differences that are both forced:
 *
 * **Nothing found means re-check, not stop.** For a schedule, no next fire time means
 * never again. For a calendar it means only that nothing is in the diary *yet* — somebody
 * may add an appointment in ten minutes — so an empty look-ahead arms a re-check instead
 * of ending the flow.
 *
 * **The loop also wakes on a calendar change.** An appointment moved an hour earlier after
 * its alarm was armed would otherwise fire an hour late, silently. That is why this
 * trigger arms a calendar watch as well as an alarm, even though it is not the node that
 * is *about* changes. Waking early costs one query; not waking costs the macro.
 *
 * **Two things it deliberately does not do.** It does not fire for an appointment that
 * already began before the macro was armed — the planner only ever looks forward, so a
 * newly-enabled macro does not replay this morning. And a pending alarm does not survive
 * the process being killed by anything other than the engine itself, for
 * `action.wait_until`'s reason: alarm-backed means it survives doze, not a restart. The
 * arm is recreated on boot along with the whole macro, and the planner recomputes from
 * scratch, so the practical effect is limited to appointments due in the seconds a reboot
 * takes.
 */
class CalendarEventTrigger : Trigger<CalendarEventTriggerConfig, CalendarEvent> {

    override val definition = triggerNode<CalendarEventTriggerConfig, CalendarEvent>(
        typeId = "trigger.calendar_event",
        displayName = "Calendar Event",
        description = "Starts when an appointment begins, ends, or its reminder falls due",
        category = NodeCategory.TIME_SCHEDULE,
        icon = NodeIcon.CALENDAR,
        output = dataOut<CalendarEvent>("event", label = "Event"),
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.READ_CALENDAR.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "calendar.read",
            ),
        ),
    )

    override fun activate(
        config: CalendarEventTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<CalendarEvent>> = flow {
        val spec = config.toSpec()
        val fired = FiredOccurrences()
        val watch = host.armCalendarWatch(node.id) { message, level -> host.report(node, message, level) }
        var alarm: ScheduleHandle? = null
        var announcedNothing = false
        try {
            while (true) {
                val now = System.currentTimeMillis()
                val next = host.nextCalendarOccurrence(spec, now)
                if (next == null && !announcedNothing) {
                    announcedNothing = true
                    host.report(node, nothingFoundMessage(config))
                }
                if (next != null) announcedNothing = false

                alarm?.cancel()
                alarm = host.armAlarm(node.id, next?.atEpochMs ?: (now + RECHECK_MS))

                val bus = host.busEvents()
                    .filter { it.triggerNodeId == node.id }
                    .filter { it.source == TriggerSource.SCHEDULE || it.source == TriggerSource.CALENDAR }
                    .first()

                // A CALENDAR arrival falls through and re-plans, which is the whole point
                // of watching: the appointment may have moved, been deleted, or only just
                // been created.
                if (bus.source == TriggerSource.SCHEDULE && next != null && fired.add(next.key)) {
                    emit(NodeOutput(next.event.toItem()))
                }
            }
        } finally {
            alarm?.cancel()
            watch.cancel()
        }
    }

    /**
     * Said once, into the macro's own console, when the look-ahead is empty.
     *
     * The reminder mode gets its own sentence because its empty case has a cause somebody
     * can act on and would never guess: an appointment with no reminder set on it is
     * invisible to that mode, however soon it is.
     */
    private fun nothingFoundMessage(config: CalendarEventTriggerConfig): String =
        if (config.fireWhen == CalendarWhen.REMINDER) {
            "No appointment with a reminder on it in the next ${CalendarLimits.HORIZON_DAYS} days. " +
                "This mode only sees appointments that have a reminder set."
        } else {
            "No appointment to wait for in the next ${CalendarLimits.HORIZON_DAYS} days; checking again later."
        }

    private companion object {
        val RECHECK_MS = CalendarLimits.RECHECK_MINUTES * 60 * 1000
    }
}
