---
title: How a macro runs
description: Arming, the engine, what a problem actually blocks, and why one bad wire does not stop everything.
sidebar:
  order: 6
---

## Arming

A macro's switch means **armed**: its triggers are registered with the platform, and it
can fire. Editing an armed macro re-arms it.

The engine lives in a **foreground service** that owns the execution, survives the UI
being destroyed, and re-arms every enabled macro on boot. It stops itself when nothing
is armed and nothing is waiting.

Manual runs are different: a **Manual Trigger** registers nothing at all. Every manual
run walks the graph from that one node with no arm behind it, which is what lets an
*armed* macro be run by hand and lets a manual button work in a graph full of other
triggers.

Widgets, launcher shortcuts and the card's own Run button all go through that same
path.

## Validity, and what a problem costs

Every finding the validator produces carries two separate things: **where it is** —
what to badge and what to colour — and **what it costs**.

Quarantine is **the smallest thing that is actually broken**:

| Problem | What it blocks |
| --- | --- |
| A bad execution wire | That wire |
| A cycle | The one wire that closes the loop — every node on it still runs once |
| A bad data wire | The node that *reads* it, resolved through the chain, so a chain of transforms blocks the action at the far end |
| A warning | **Nothing at all** |

An invalid macro is **not refused**. The engine logs one summary line and runs
everything not named, so a loop in one trigger's branch leaves every sibling branch and
every other trigger working. An all-or-nothing gate would make one bad wire
indistinguishable from a macro that had never been armed.

Blocking a node for a broken data wire is a different stance from a *runtime* failure —
a JSON path that does not match, a script that throws — which lands on the node's
fallback and carries on. That is a well-formed graph failing at run time, which you
configured a fallback for. This is structural invalidity, where falling back would
quietly substitute a form value for a wire you can see on the canvas.

## Four things that warn and block nothing

- **A missing permission.** The graph is perfect; the phone is not. Granting it starts
  the node working with no edit here at all.
- **Hardware the phone does not have.** There is no Settings page for this either — but
  the macro is not broken, it is *portable*, and quarantining the node would take out
  work on the phone that can run it.
- **A reference to a deleted variable or macro.** The node already reports that nothing
  happened; there was just never anything saying why.
- **A plugin that says it is not ready.** Its own sentence — "nobody is signed in" —
  lands on every one of its placed nodes.

## Failure isolation

Failures are contained at four levels, and each was a real bug once:

- **Per node.** One action throwing does not take out its siblings.
- **Per event.** One bad run must not unsubscribe a geofence for the rest of the arm.
- **Per trigger source.** A dead flow reports and its siblings keep collecting.
- **Per macro.** One macro that cannot arm used to silently skip every macro after it
  on boot. It no longer does.

Stopping a run stops it, rather than logging a bogus action failure and walking on.

## Where it all lives

Workflows are individual JSON files in the app's private storage. Deserialization is
lenient, so a file written by an older build loads unchanged as long as every added
field has a default.

The schema version **gates load**: a workflow older than the current version is
discarded rather than migrated. In practice the version moves very rarely, precisely
because of that — adding a field with a default needs no bump.

A macro's run log goes with it when it is deleted.
