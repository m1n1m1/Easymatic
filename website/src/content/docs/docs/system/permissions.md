---
title: Permissions
description: Every grant the app can want, what it is for, and which ones fail silently.
sidebar:
  order: 1
---

Easymatic asks for a permission when a node needs one, and never up front. **Setup →
Permissions** is the screen that answers the other question: *what does this app need,
and what has it got?*

The screen is in two halves, and which half an entry lands in is derived rather than
declared:

- grants **some node declares**, grouped so that seven nodes wanting overlay access are
  one row naming seven nodes;
- grants **no node declares** and which therefore had no home anywhere — unrestricted
  battery use, exact alarms, modify system settings.

Every row has one of three buttons: **Grant** where a tap really does produce the system
dialog, **Open settings** where you have to go and find a switch, and **Revoke** which
takes you to where that is done. There is no in-app revoke, because Android has no such
API that does not kill the process.

## Special access

These are the ones granted from a Settings page rather than a dialog, and the ones most
likely to be the reason a macro does nothing.

| Grant | What it lets a macro do |
| --- | --- |
| **Draw over other apps** | Put a dialog on screen, and open other apps, while you are looking at something else. **Android blocks both from a background app without it.** |
| **Notification access** | See notifications from other apps, so a macro can react to one |
| **Do Not Disturb access** | Turn Do Not Disturb on and off, and switch the ringer to silent |
| **Accessibility access** | See volume and power button presses, and capture the screen for *Take Screenshot*. It never watches what is on screen |
| **Unrestricted battery use** | Keep Android from stopping the engine. Without it macros may not re-arm after a reboot and time-based triggers can be delayed |
| **Alarms & reminders** | Let a schedule fire, and a wait end, **at the minute you asked for**. Without it Android is free to batch it with other work |
| **Modify system settings** | Change screen brightness, screen timeout and auto-rotate |
| **Media management** | Change, move or delete photos **without confirming each one**. Optional — without it everything still works, Android just asks every time, which needs you to be holding the phone |
| **Device administrator** | Be told when somebody enters the wrong PIN, pattern or password. It is the only way Android reports a failed unlock. **While this is on, Easymatic cannot be uninstalled until you turn it off again** |

## Runtime permissions

The ordinary dialog kind: Location, Approximate location, **Location in the background**,
Receive texts, Send texts, Make calls, Contacts, Calendar, Change your calendar,
Notifications, Bluetooth, Camera, Microphone, Photos, Photo locations.

Two are worth calling out.

**Location in the background** is granted by choosing *"Allow all the time"*, and modern
Android does not offer it in the first dialog. A geofence needs it to work while
Easymatic is closed — which is the only time it is any use — and without it the geofence
is simply never registered, with nothing crashing and nothing logged.

**Contacts** is only needed to *dial* a number, not to choose one. Choosing a contact
needs nothing at all; looking their number up later does.

## Permissions the phone has stopped offering

A permission the running Android version has never heard of counts as **granted**,
because a row saying "not granted" about something with nothing to grant is a permanent
false alarm.

The same reasoning applies to **NFC on a phone with no chip**: the row reports satisfied
rather than showing a switch that can never go green. The NFC *trigger* reports the
missing hardware itself, in its own console, where the person who placed the node will
see it.

## Hardware is a different axis

Hardware the phone does not have is deliberately **not** on this screen. A permission is
something you can go and fix; a capability is a fact about the phone. It shows up on the
node's own card and in the Problems panel, and it blocks nothing — the macro is not
broken, it is *portable*.

## What a missing permission does to a macro

It is a **warning**, not a block. The graph is perfect and the phone is not, and flipping
a switch in Settings starts it working with no edit at all.

What that warning buys is **reach**. A node's config form has always shown this, but only
to somebody who thought to open that node — and a macro missing a grant is exactly the
one that looks fine from outside: the geofence that is never registered, the *Launch App*
that Android drops because the app is in the background. Both fail silently, and both
look identical to a macro that is simply waiting.

Grants are re-read every time you come back to the app, because every one of these is
granted by leaving it.
