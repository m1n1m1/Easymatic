package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.pulseTriggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/**
 * Trigger for `trigger.calendar_changed`. Fires when anything in the phone's calendars is
 * added, changed or removed.
 *
 * **It carries no data, and that is the honest shape rather than a shortfall.** The
 * provider's observer says *that* something changed and nothing whatever about what — no
 * appointment id, no calendar, no kind of change. An `event` port here would be a socket
 * that hands the consumer nothing, which is exactly what `NodeDeclarationContractTest`
 * refuses. So it pulses, and `action.calendar_query` after it is the composition: fire,
 * look, decide.
 *
 * **And it takes no config for the same reason.** A "Calendar" field would render
 * perfectly and do nothing at all, because there is nothing in the notification to filter
 * on — the failure `@Picker` exists to prevent, one layer out. Narrowing to one calendar
 * means querying it after the pulse.
 *
 * **Expect it to fire on syncs.** Anything that touches the calendar counts, including a
 * background account sync pulling changes made on another device — which is often what
 * somebody wants and is occasionally a surprise. The notifications are coalesced, so one
 * sync is one fire rather than one per appointment, but a phone that syncs three accounts
 * on a schedule will see this trigger from time to time with nothing visibly different in
 * the diary.
 */
class CalendarChangedTrigger : Trigger<NoConfig, Unit> {

    override val definition = pulseTriggerNode<NoConfig>(
        typeId = "trigger.calendar_changed",
        displayName = "Calendar Changed",
        description = "Starts when an appointment is added, changed or removed in any calendar",
        category = NodeCategory.TIME_SCHEDULE,
        icon = NodeIcon.CALENDAR,
        // The first pulse trigger to need one: every other is a broadcast the platform
        // hands over freely, where registering a calendar observer without this hears
        // nothing and says nothing about why.
        permissions = listOf(READ_CALENDAR),
    )

    override fun activate(config: NoConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> = flow {
        val watch = host.armCalendarWatch(node.id) { message, level -> host.report(node, message, level) }
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.CALENDAR && it.triggerNodeId == node.id }
                .collect { emit(NodeOutput(Unit)) }
        } finally {
            watch.cancel()
        }
    }

    private companion object {
        val READ_CALENDAR = PermissionRequirement(
            manifestPermission = Permissions.READ_CALENDAR.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "calendar.read",
        )
    }
}
