---
title: Install and first run
description: What to install, what to allow, and the two screens the whole app is made of.
sidebar:
  order: 1
---

Easymatic needs Android 8.0 (API 26) or later. Get the APK from the project's
[releases](https://github.com/m1n1m1/Easymatic/releases), or build it yourself from the
[repository](https://github.com/m1n1m1/Easymatic).

## The two tabs

Everything in the app is behind one of two tabs at the bottom of the home screen.

**Workflows** is your list of macros. Each row has a switch: on means *armed* — its
triggers are registered and the macro can fire. Tapping a row opens it in the editor.
The row also carries a problem count when the graph has something wrong with it, and a
search bar sits above the list.

**Setup** is everything configured once and shared by every macro, in three groups:

| Group | Rows | What they are |
| --- | --- | --- |
| Connections | Smart home, AI, Mail accounts | Things outside the phone, each of which can fail in ways you have to go and fix |
| Libraries | Global variables, Geofences, NFC tags, Folder access | Your own records, which macros refer to by name |
| System | Permissions, Plugins, App access | What the phone and other apps allow — Easymatic only reports these, it does not decide them |

You do not have to set any of it up before writing your first macro. A node that needs
something you have not configured says so on its own card and in the Problems panel.

## What to allow first

Easymatic asks for permissions the way every node asks: **only when a node needs one**.
There is no wall of requests at first launch, and a macro that only reads the battery
level and posts a notification needs almost nothing.

Two grants are worth giving straight away anyway, because both fail *silently* and
neither belongs to any one node.

**Notifications** (`POST_NOTIFICATIONS`, Android 13+). Easymatic runs its engine as a
foreground service, and a foreground service needs a notification to show. Without the
grant the service still runs, but you lose the one indicator that says it is running.

**Battery optimisation exemption.** This is the one that decides whether *anything*
re-arms after a reboot, and whether long-running macros survive the phone deciding to
tidy up. Open **Setup → Permissions** and grant it from there — see
[Running in the background](/docs/system/background/) for why it matters and what else
some manufacturers add on top.

## Where things are

- Each macro's own **Problems**, **Console** and **Variables** live in the editor's
  bottom bar, not on a settings screen — they are about that macro.
- **Permissions** lists every grant the app can want, whether or not a node declares
  it, and says which nodes need each one.
- Nothing is reachable from outside the app by default. A macro can only be run by
  another app once you place a *Called by Another App* trigger on it — see
  [the process API](/docs/extend/process-api/).

## Next

Build something: [Your first macro](/docs/start/first-macro/).
