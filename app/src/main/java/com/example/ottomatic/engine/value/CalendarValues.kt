package com.example.ottomatic.engine.value

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.CalendarEvent
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.action.toItem
import com.example.ottomatic.engine.valueNode

/**
 * The grant both calendar values need, and the one thing about them that looks like a
 * breach of the pull-side contract and is not.
 *
 * A value node **may** declare a permission — `value.wifi_network` settled that on
 * 2026-08-07 — and declaring it is what makes a missing grant *visible*, since the
 * Problems panel, the Permissions screen and the node's own card all walk these
 * declarations. A value that stayed silent about its grant would read null for ever with
 * nothing anywhere saying why. What the grant does not buy is permission to fail loudly:
 * both readers below still answer null and let the consumer fall back.
 */
private val READ_CALENDAR_PERMISSION = PermissionRequirement(
    manifestPermission = Permissions.READ_CALENDAR.manifest,
    type = PrerequisiteType.RUNTIME,
    rationaleKey = "calendar.read",
)

/**
 * `value.calendar_busy` — whether an appointment is on right now.
 *
 * **The node that looks like it breaks the pull-side rule and does not**, and the
 * argument is `value.ha_state`'s taken one step further because it has to be: there is no
 * push channel here at all. There is no `value.light_state` because every member of that
 * facade is a *network* round trip to a device that may be unplugged — slow and failable,
 * the two things a pulled value may not be. This is a binder call into a local database a
 * sync adapter has already filled: no socket, no credential, no DNS, no timeout. **The
 * bar was never "must not concern the network"; it was "must be cheap and must not
 * fail"**, and a local provider clears it the way a push channel does, by a different
 * road. It is `value.wifi_network`'s side of the line.
 *
 * **No config, deliberately**, and that is what this node is *for*. A `val:` read is
 * performed with no config at all, so a calendar filter here would put it in
 * `CONFIGURED_VALUE_TYPE_IDS` beside `value.variable` — and being namable in
 * `action.if`'s source dropdown with no edge drawn is the entire point of the node.
 * "When I get home, **if** I am not in a meeting, put music on" is one comparison on the
 * canvas because of this decision. Narrowing to one calendar is
 * `action.calendar_query` into `transform.list_count`, on the exec wire.
 *
 * **`false` and `null` are different answers.** False means the read succeeded and
 * nothing is on; null means it could not be read — no grant, no provider — so the
 * consumer falls back to its own form value and a comparison fails closed. Collapsing
 * them would make a revoked permission look like a free afternoon.
 *
 * What counts as busy — cancelled, declined, "shows me as free" and all-day appointments
 * all do not — lives in
 * [CalendarBusy][com.example.ottomatic.domain.model.CalendarBusy], shared with the query
 * action and the trigger and argued for there. The all-day exclusion is the one to know
 * about: a birthday or holiday feed would otherwise make this answer yes on most days of
 * the year.
 */
class CalendarBusyValue : ValueNode<NoConfig, Boolean> {

    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.calendar_busy",
        displayName = "In an appointment",
        description = "Whether an appointment is on in any calendar right now",
        category = NodeCategory.VALUE_TIME,
        icon = NodeIcon.CALENDAR,
        output = dataOut("busy", label = "Busy"),
        permissions = listOf(READ_CALENDAR_PERMISSION),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): Boolean? =
        context.calendars.busyNow()
}

/**
 * `value.calendar_next` — the next appointment due to begin.
 *
 * [CalendarBusyValue]'s sibling and its reasoning throughout, with one difference worth
 * stating because it looks like it contradicts `value.ha_state`: **this one answers a
 * struct rather than a scalar.**
 *
 * That reads at first like the ceremony `value.ha_state` refuses — an `action.break` in
 * front of every comparison — and it is not, because a `val:` source's schema is resolved
 * at design time: `action.if` offered a *struct* replaces its field row with the struct's
 * own field names, so "next appointment · startsAt · is before · 09:00" is a single node
 * with nothing wired. A scalar would have forced a choice between the start time and the
 * title, and "when does it start" and "what is it called" are both what people ask.
 *
 * An appointment already running does **not** count as the next one: "next" means the
 * next to begin, which is what makes "how long until my next meeting" answerable. Unlike
 * [CalendarBusyValue] an all-day appointment does count here — a public holiday is an
 * answer to "what is next", where it is not an answer to "am I busy".
 *
 * Null when there is nothing in the next three months, and null when it could not be
 * read. Those are one answer here rather than two, which is the pull side's price and the
 * reason `action.calendar_query` exists beside it.
 */
class CalendarNextValue : ValueNode<NoConfig, CalendarEvent> {

    override val definition = valueNode<NoConfig, CalendarEvent>(
        typeId = "value.calendar_next",
        displayName = "Next appointment",
        description = "The next appointment due to start in any calendar",
        category = NodeCategory.VALUE_TIME,
        icon = NodeIcon.CALENDAR,
        output = dataOut("event", label = "Event"),
        permissions = listOf(READ_CALENDAR_PERMISSION),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): CalendarEvent? =
        context.calendars.nextEvent()?.toItem()
}
