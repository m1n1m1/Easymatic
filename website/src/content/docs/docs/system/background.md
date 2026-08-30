---
title: Running in the background
description: What keeps the engine alive, what kills it, and what to do about each.
sidebar:
  order: 2
---

An automation app is only as good as its ability to still be running an hour later. This
page is the checklist.

## What Easymatic does

The engine runs as a **foreground service**, which is Android's own mechanism for "this
app is doing something the user asked for". It owns the executor, survives the UI being
destroyed, and re-arms every enabled macro after a reboot.

It **stops itself** when nothing is armed and nothing is waiting, so a phone with no
enabled macros pays nothing.

## The three grants that decide whether it stays running

**Unrestricted battery use.** This is the important one. Without it Android is free to
stop the engine in the background, and macros may not re-arm after a reboot. Grant it
from **Setup → Permissions**.

**Notifications**, so the foreground service can show its notification. Without it the
service still runs but you lose the indicator that says so.

**Alarms & reminders**, if you use schedules or *Wait Until*. Without it Android batches
your alarm with other work, so "at 07:30" becomes "some time after 07:30".

## Manufacturer restrictions

Several manufacturers add their own layer on top of Android's, and none of it is visible
to the app. If macros stop firing overnight on a phone from one of these brands, look
for a setting like:

- **Xiaomi / Redmi / POCO** — *Autostart*, and Battery saver → *No restrictions*
- **Samsung** — *Never sleeping apps*, and turn off *Put unused apps to sleep*
- **Huawei / Honor** — *App launch* → *Manage manually*, with all three switches on
- **Oppo / realme / OnePlus** — *Allow background activity* / *Allow auto launch*
- **Vivo / iQOO** — *High background power consumption* allowlist

The community site [dontkillmyapp.com](https://dontkillmyapp.com) documents these per
manufacturer and is worth a look before assuming a bug.

## Boot

Enabled macros re-arm on boot. That path is where the battery exemption matters most,
and it is why Easymatic prompts about the exemption when a boot start has failed *and*
the exemption is genuinely missing.

On Android 12 and later a boot start can fail for reasons that have nothing to do with
battery optimisation, which is why the prompt checks both.

## What a background-started macro cannot do

Android blocks two things from a background app outright, and both are what the
**Draw over other apps** permission unblocks:

- putting a dialog on screen — the *Ask the user* nodes;
- opening another app — *Launch App*, *Send Message*, and the consent dialogs the
  picture-changing nodes need.

Without that grant these fail **silently**: nothing throws, nothing is logged by
Android, and the macro looks like it ran.

## Long-running macros

A macro containing a **Wait** or a **Wait Until** keeps the service up until the wait is
done, including for a run started by a widget or a shortcut where no macro is armed at
all.

There is one case where that protection does not apply: a macro started by an **external
broadcast** from another app. Android 12 and later forbid starting a foreground service
from the background, and a third-party broadcast is on no exemption list — so a long
macro started that way runs without the service protecting it from the process being
reaped. Short macros are unaffected. If you are writing the caller and the macro is
long, use the provider door instead — see
[the process API](/docs/extend/process-api/).

## When something still does not fire

1. Open the macro's **Console**. A run that started leaves a `→ node` line for every
   node it reached.
2. Open **Problems**. A missing permission, missing hardware or a dangling reference is
   listed there and badged on the bar.
3. Check the macro's **switch** is actually on. An external call additionally requires
   it — unlike a widget tap, where you can see it is off and mean it anyway.

See also [Troubleshooting](/docs/help/troubleshooting/).
