package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.CalendarEventRecord
import io.github.m1n1m1.easymatic.domain.model.items.CalendarEvent
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime

private const val MS_PER_MINUTE = 60_000L

/**
 * The facade's reading of an appointment, as the graph's item.
 *
 * Sits beside the three calendar actions rather than inside any one of them, because the
 * query action, the value node and the trigger all emit exactly this — and the field they
 * would each be tempted to compute separately is [CalendarEvent.durationMinutes], which
 * is derived here once. Two nodes deriving a duration separately is how they eventually
 * disagree about an all-day appointment.
 *
 * `MailMessageData.toItem`'s arrangement, with one difference: the `ref` is *not* minted
 * here. A mail ref is a graph concept the transport knows nothing about; a calendar ref
 * carries the provider's own instance time, which only the provider can supply, so
 * `AndroidCalendars` mints it and this passes it through.
 */
internal fun CalendarEventRecord.toItem(): CalendarEvent = CalendarEvent(
    ref = ref,
    title = title,
    description = description,
    location = location,
    startsAt = DateTime(startEpochMs),
    endsAt = DateTime(endEpochMs),
    allDay = allDay,
    durationMinutes = ((endEpochMs - startEpochMs) / MS_PER_MINUTE).coerceAtLeast(0),
    calendar = calendarName,
    calendarRef = calendarRef,
    organiser = organiser,
    availability = availability,
    status = status,
    recurring = recurring,
    reminderMinutes = reminderMinutes,
)
