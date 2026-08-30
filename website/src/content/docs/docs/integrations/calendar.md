---
title: Calendar
description: Seven nodes over the phone's own calendar provider — no account to add.
sidebar:
  order: 7
---

There is **nothing to configure**. Easymatic reads and writes through the phone's own
calendar provider, which is the one channel every calendar app already speaks — so
Google, Exchange, CalDAV and a local calendar all work with no OAuth, no
`google-services.json` and no network.

What it costs is two permissions, **read calendar** and **write calendar**, declared per
node.

## The nodes

| Node | Kind | What it does |
| --- | --- | --- |
| **Query appointments** | Action | Reads a window onto a list you can loop over |
| **Add appointment** | Action | Creates one, and hands back a reference |
| **Update appointment** | Action | Changes or deletes one |
| **When an appointment happens** | Trigger | Fires at a start, an end, or a reminder |
| **When the calendar changes** | Trigger | Fires when anything is added, moved or removed |
| **Busy now** | Value | Whether you are in an appointment right now |
| **Next appointment** | Value | The next one, as a struct |

The three actions mirror the mail family exactly — read, create, act on what was read.

## Two value nodes with no config

Both take **no configuration at all**, deliberately. An on-demand read in an *If* node
is performed with no config, so a calendar filter on these would cost them the dropdown
entry that is the whole point of them.

**Next appointment answers a struct**, and *If* handles that: offered a struct, its
comparison replaces the plain value row with that struct's own field names. So
*"Next appointment · startsAt · is before · 09:00"* is one node with nothing wired.

A calendar query is one call into a local database a sync adapter has already filled —
no socket, no credential, no DNS, no timeout — which is why it clears the pull side's
"cheap and cannot fail" bar despite not being pushed.

## Recurring appointments

Reads expand recurrences. A weekly stand-up shows up in every week, not only the week it
was created in.

**Update appointment defaults to "only this appointment"**, and that default is a safety
choice: deleting one occurrence is undone by hand in a minute, where deleting the series
takes away every stand-up there will ever be — and looks like the macro working until
the following week.

An occurrence that has *already* been edited is no longer part of its series, which is
the one case that behaves like none of the others.

## All-day appointments

The provider stores an all-day appointment at **midnight UTC**, because a date has no
hour to be in a timezone. Easymatic converts in both directions in one place, so an
all-day appointment on the 3rd does not become "starts on the 2nd at 23:00" in Vienna.

If you compare an all-day appointment's times yourself, remember its end is
**exclusive**.

## The reminder trigger

All three modes of *When an appointment happens* — starts, ends and reminder — run on
the same re-armed exact-alarm loop, differing only in where the moment comes from.

That is not a shortcut. The platform's own reminder broadcast is implicit, is on no
delivery exemption list, and so reaches a manifest receiver **never** — it works only
while the process happens to be alive, which is the one moment a trigger must not depend
on. Using an alarm instead also makes it work on phones whose manufacturer's calendar
has replaced the provider's alert path entirely.

Two behaviours follow:

- **Nothing found means re-check, not stop.** For a schedule, no next fire time means
  never again; for a calendar it means only that nothing is in the diary *yet*.
- **The loop also wakes on a calendar change**, because an appointment moved after its
  alarm was armed would otherwise fire at the old time, silently. That is why the event
  trigger watches for changes too, not only the trigger that is *about* changes.

Exact alarms may need a grant of their own on recent Android versions — see
[Permissions](/docs/system/permissions/).
