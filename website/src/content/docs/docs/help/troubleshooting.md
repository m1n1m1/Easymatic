---
title: Troubleshooting
description: The macro did not fire, the node did nothing, the run stopped halfway.
sidebar:
  order: 1
---

Three places answer almost everything, and they answer different questions:

- **Problems** (bottom bar) — what the graph *is*. A permission, a bad wire, a dangling
  reference.
- **Console** (bottom bar) — what a run *did*. Set the filter to **All** to see the
  per-node trace.
- **Setup → Permissions** — what the phone allows.

## The macro never fires

**Is its switch on?** Arming is what registers triggers. Editing an armed macro re-arms
it; that is normal.

**Is there a warning on the trigger's card?** A missing permission is the commonest
cause, and most of these fail *silently* at the platform level:

| Trigger | Needs | Symptom without it |
| --- | --- | --- |
| Geofence | Location, **and "Allow all the time"** | Never registered. Nothing logged |
| Message / Notification | Notification access | Nothing arrives |
| Picture Saved | Photos | Nothing arrives |
| NFC Tag | NFC switched on | Nothing arrives, and **never** while the screen is off or locked |
| SMS Received | Receive texts | Nothing arrives |

**Is it a schedule that runs late?** Grant **Alarms & reminders**. Without it Android is
free to batch your alarm with other work.

**Does it stop firing overnight?** Grant **Unrestricted battery use**, and check whether
your phone's manufacturer adds a layer of its own — see
[Running in the background](/docs/system/background/).

**Did it stop after a reboot?** Same grant. Enabled macros re-arm on boot, and that path
is where the exemption matters most.

## A node runs and does nothing

Open the **Console** with the filter on **All**. A node that ran leaves a `→ node` line;
if there is no such line, the run never reached it.

Three common causes:

**A permission the platform does not report as an error.**
*Launch App*, *Send Message* and the *Ask the user* dialogs are dropped outright by
Android when started from the background without **Draw over other apps**. Nothing throws.
*Brightness*, *Screen timeout* and *Auto-rotate* need **Modify system settings** and
report "did nothing" without it.

**A reference to something that has been deleted.** A variable, a macro, a smart-home hub
or a mail account. The node reports that nothing happened; Problems says which.

**A value that arrived as nothing.** The `in` and `out` lines in the console carry the
data that actually crossed each wire. A blank where you expected a number is usually a
JSON path that matched the wrong field, or a value node answering null because its grant
is missing.

## The run stops halfway

**At an *If*.** Check which branch fired. The console's `→ node` lines say what ran after
it; an unwired branch simply ends that pass.

**At a *Stop Macro*.** That is what it does — it halts the current chain.

**At a node with an error.** A blocked node is quarantined, and only it: the console logs
one summary line and everything not named still runs. Problems says what was blocked and
why.

**After a *Wait Until*.** That node's *Carry on now* branch fires immediately and its
*When the time comes* branch fires later. If you wired the wrong one, the second half
never runs. Wiring one branch into the other is an error and Problems reports it.

## A drop is refused

The types do not match and no conversion exists. Every primitive pair converts, and
anything converts to text — but **nothing converts to a struct** and nothing autocasts
into a list. Making one of those is a visible node's job: **Break Struct**, **Split text**,
**Read from JSON**.

You also cannot connect an execution port to a data port.

## An integration says it cannot connect

**Hue** — the bridge's certificate rotates on a firmware update. The hub detail screen
offers *Trust new certificate*, showing both fingerprints. Only accept it if you reset or
replaced your bridge.

**Home Assistant / MQTT** — check the address includes the scheme and the port. An
`http://` address is refused for an MQTT broker, because pasting the broker's *dashboard*
URL is the likeliest mistake that field sees.

**Mail** — you almost certainly need an **app password**, not your account password. See
[Mail](/docs/integrations/mail/). Outlook.com and Microsoft 365 cannot be added at all.

**AI** — a self-hosted base URL must include the scheme; unlike other URL fields it does
not guess. OpenRouter and self-hosted servers must name a model id.

## A plugin's nodes are missing

Open **Setup → Plugins**. A plugin that is merely installed contributes nothing until you
enable it, and that screen lists **any node that was refused, with the reason**.

A plugin re-signed by a different developer lands back disabled.

## Nothing here helped

Open the failing entry in the console and use its **copy** button — it carries the run id,
which is the only thing that untangles two interleaved runs — and file it on
[GitHub](https://github.com/m1n1m1/Ottomatic).
