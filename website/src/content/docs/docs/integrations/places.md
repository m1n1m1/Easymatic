---
title: Places and geofences
description: The places library, the four transitions, and the permission that fails silently.
sidebar:
  order: 8
---

A geofence is a **shared place**, not coordinates typed into a node. Save "Home" once in
**Setup → Geofences**, and every macro that references it follows when you move house.

## Saving a place

The place editor is a map with a draggable pin and a radius. Give it a name.

**Pick a radius comfortably larger than the accuracy you will actually get.** A hundred
metres is usually the smallest that behaves. A tight fence on a phone whose fix is
kilometres wide at three in the morning is the classic source of phantom crossings.

Editing a place **re-arms live macros**, because a trigger reads its place only when it
arms — so the change reaches macros that are already running.

## The four transitions

The **Geofence** trigger points at a place and has four switches:

| Switch | Fires |
| --- | --- |
| **On enter** | As you cross in. The only one that starts on |
| **On exit** | As you cross out |
| **On dwell** | After a delay inside, default 30 s. Use it to tell *arrived* from *drove past* |
| **On staying away** | After a number of minutes outside, default 30 |

Exit is the *moment* of leaving; away is the *state* of having been gone a while. The
platform offers nothing that means "outside for a while", so *away* is Ottomatic's own
timer — started when the exit arrives, cancelled when an enter does.

With all four switched off the node registers nothing and can never fire. The editor
warns about it.

## The permission that fails silently

Two grants, and the second is the one that catches people:

- **Location access**;
- **Location access set to "Allow all the time"** — which modern Android does *not*
  offer in the first dialog. It has to be turned on in Settings.

Without the second one **the geofence is never registered**. Nothing crashes and nothing
is logged, so it looks exactly like a geofence that is waiting. That is why the node
badges itself in the Problems panel, and why the Permissions screen lists it.

## Phantom exits, and what is done about them

Users of every geofencing app report the same asymmetry: **exits are spurious, enters
are not.**

That is not about location quality. It is that you *sleep outside the fence you watch* —
so when the platform re-derives your position after a registration and announces it as an
ordinary exit, it can only ever be an exit. The process being reaped and resurrected all
night by alarms is what makes it happen at three in the morning.

Three rules filter it, and they are worth knowing because they explain the behaviour you
will see:

- **Where you were is remembered across process death.** The artefact fires *because*
  the process died, so an in-memory belief would be cleared exactly when it was needed.
- **A fix wider than the fence is ignored**, and teaches nothing — otherwise the next
  good fix would be judged against noise.
- **A fix whose whole uncertainty circle falls on one side outranks what was
  remembered**, so a genuinely missed arrival cannot swallow every departure after it.

The practical upshot: a fence needs a sensible radius, and a real crossing may take up
to a couple of minutes to be reported. Android trades promptness against battery here
and there is no setting for it.

## Known limitations

- **Arming a macro while already inside the area fires *On enter* at once.**
- **Arming while already away does not start the away countdown.** Counting begins on
  the next return and departure.
- The `away` event carries **no position** — its latitude, longitude and accuracy are
  empty, because nothing measured anything.
