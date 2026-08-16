---
name: calendar
description: Read before touching the calendar nodes - action.calendar_query, action.calendar_add, action.calendar_update, trigger.calendar_event, trigger.calendar_changed, value.calendar_busy, value.calendar_next, EventTimes, CalendarPlan and CalendarDirectory.
---

# Calendar

### Calendar

Three actions, two triggers and **two value nodes**, over the platform's own calendar
provider and no vendor at all — `action.calendar_query`, `action.calendar_add`,
`action.calendar_update`, `trigger.calendar_event`, `trigger.calendar_changed`,
`value.calendar_busy`, `value.calendar_next`. That is the messengers' bet in a second
setting: `CalendarContract` is the one channel every calendar app on the phone already
speaks, so Google, Exchange, CalDAV and a local calendar all work with no OAuth, no
`google-services.json`, no new dependency and no network. What it costs is
`READ_CALENDAR` and `WRITE_CALENDAR`, declared per node.

The three actions mirror the **mail family** exactly — read, create, act on what was read
— and are three rather than one for the light nodes' reason: the *declared ports* differ.
`query` has no `ref` input and a list output, `add` has no `ref` input and hands one back,
`update` takes one. Inside `update`, changing and deleting are one node and one enum,
which is `action.mail_update`'s call.

**This is the first facade both sides of the graph may touch that has no push channel at
all**, and that is the interesting part rather than the calendar itself. `value.ha_state`
earned the pull side with a websocket keeping a map warm, which read at the time as *a
push channel is the exception*. The rule underneath was always **cheap, and cannot fail**.
A calendar query is one binder call into a local database a sync adapter has already
filled — no socket, no credential, no DNS, no timeout — so it clears the bar by a
different road, and it is `value.wifi_network`'s side of the line rather than
`value.light_state`'s. The fact that separates it from `Files` is the one `Files.kt` names
itself: a granted folder may be served by a cloud provider, and a calendar cannot be.
Both values take **no config**, deliberately: a `val:` read is performed with no config,
so a calendar filter would put them in `CONFIGURED_VALUE_TYPE_IDS` and cost the
`action.if` dropdown entry that is the whole point of them.

**`value.calendar_next` answers a struct, unlike `value.ha_state`**, and it is not the
ceremony that node refuses: a `val:` source's schema is resolved at design time, so
`action.if` offered a struct replaces its field row with that struct's own field names
(`compareStructFields`). "Next appointment · startsAt · is before · 09:00" is one node
with nothing wired.

**Reads go through `Instances`, never `Events`.** `Events` holds one row per *series* plus
a recurrence rule; `Instances` expands it. Query `Events` for "this week" and a weekly
stand-up is invisible in every week but the one it was created in — which looks like an
empty calendar rather than the wrong table.

**The reminder trigger cannot use `ACTION_EVENT_REMINDER`**, and this is the trap worth
knowing before anybody "fixes" it. The constant exists and the provider really does
broadcast it — but it is an *implicit* broadcast and is not on Android 8's exemption
list, so a manifest receiver is never delivered, and a runtime-registered one works only
while the process happens to be alive, which is the one moment a trigger must not depend
on. So all three of `trigger.calendar_event`'s modes are **one mechanism**: the re-armed
exact-alarm loop from `ScheduleTrigger.alarmFlow`, differing only in where the moment
comes from — a config field for starts and ends, the appointment's own `Reminders.MINUTES`
row for the reminder mode. That also makes it work on phones whose OEM calendar has
replaced the provider's alert path entirely.

Two differences from `ScheduleTrigger`'s loop, both forced. **Nothing found means re-check,
not stop** — for a schedule no next fire time means never again, where for a calendar it
means only that nothing is in the diary *yet*. And **the loop also wakes on a calendar
change**, because an appointment moved after its alarm was armed would otherwise fire at
the old time, silently; that is why the event trigger arms a calendar watch as well, not
only the trigger that is *about* changes. `planNext` and `FiredOccurrences`
(`engine/trigger/CalendarPlan.kt`) are the whole policy, pure and JVM-tested with no
provider anywhere — the platform half is one query, which is why `TriggerHost` exposes it
as a lookup on `mailAccount`'s shape.

**All-day appointments are the one thing that fails silently and plausibly.** The provider
stores them at **midnight UTC** with `ALL_DAY = 1`, because a date has no hour to be in a
timezone. Read those millis as an instant and every all-day appointment shifts by the
phone's offset — in Vienna, "all day on the 3rd" becomes "starts on the 2nd at 23:00", with
no error anywhere. `EventTimes` (`domain/model/`) is the one place that conversion happens,
in both directions, on `TimeOfDay`'s arrangement. Its end is **exclusive**, kept rather
than tidied away, because that is what makes `start <= now < end` correct for both kinds of
appointment at once.

**`action.calendar_update` defaults to "only this appointment"**, and that default is a
safety choice rather than a style one: deleting one occurrence is undone by hand in a
minute, where deleting the series takes away every stand-up there will ever be and looks
like the macro working until the following week. `CalendarEventRef` therefore carries the
**instance start beside the event id**, unshifted, because it is written straight back to
`ORIGINAL_INSTANCE_TIME` where the provider expects its own number. There are four write
paths and a fifth case that looks like none of them: an occurrence that has *already* been
edited is no longer part of its series, and inserting a second exception against it does
nothing at all — `CalendarWrites` branches on `ORIGINAL_ID` for that reason.

`IcalDuration` exists because a repeating appointment has **no end time**: it stores
`DTSTART`, a rule and a `DURATION`, leaving `DTEND` null. Reading `DTEND` instead answers
zero for exactly the appointments an occurrence edit is about, and the appointment
silently becomes an hour long.

**Two picker kinds**, `CALENDAR` and `CALENDAR_FILTER`, on `APP`/`APP_FILTER`'s reasoning:
"every calendar" is a valid answer to a filter and not to "which one do I add this to", a
property may carry only one `@Picker`, and the renderer is handed the kind and nothing
else — so `optional` cannot do this job, since it decides what the *validator* forgives
rather than what the chooser offers. Both are read-only despite needing a grant, which is
where `@WifiNetwork`'s argument was tested and declined: the node cannot work without the
same grant either way, and the half that decides — *is the answer set knowable and
complete?* — passes outright, since the provider lists every calendar there is.
`CalendarDirectory` is the **seventh hydrated registry**, and `isHydrated` matters more
here than anywhere else it appears: before the grant lands this process knows about no
calendar at all, and a directory that answered "not found" would report every calendar
node on the device as broken on a fresh install. Only a *successful* read hydrates it.

`trigger.calendar_changed` carries **no data and takes no config**, and both are honest
rather than unfinished: a `ContentObserver` says *that* something changed and nothing about
what, so an `event` port would hand the consumer nothing and a calendar field would filter
on nothing. `action.calendar_query` after the pulse is the composition. Its notifications
are coalesced, because the provider notifies once per row it touches — one account sync
pulling a dozen appointments would otherwise run the macro a dozen times.

