---
title: Variables
description: The graph's only writable state — declared first, chosen from a picker, scoped to a macro or shared.
sidebar:
  order: 5
---

Everything else in a graph is derived from what is true right now. That covers "when I
get home, turn the lights on" and not "the third time this happens today". Variables
are the difference.

**Set Variable** writes and **Read Variable** reads. **When a variable changes** is the
trigger.

Scripting does not close the gap: every script run starts with an empty isolate and
cannot remember its own previous run.

## Declared before use

A variable is **declared**, not created by being written to. A declaration carries:

- a **name** — what you call it;
- a **type** — Text, Number, Whole number, Yes or no, or Date & time;
- an **initial value**;
- a **constant** flag.

Every node that touches one holds a *reference chosen from a picker*, never a typed
name. Before declarations existed, a variable came into being the moment some node
wrote a name into it — so a typo silently named a *different* variable, which read as
unset and looked like a broken node.

Declaring makes the typo unmakeable, and makes a reference to something deleted a
warning the Problems panel can raise.

## Two scopes

**Local** variables belong to one macro. They live in the workflow file, they are
listed on the editor's Variables surface, and deleting the macro takes them with it.

**Global** variables are shared by every macro. They live in **Setup → Global
variables**, and are also reachable from the editor's Variables surface through a row
carrying the global count.

The picker shows both sections, because there the question is "which of my variables is
this?". The editor's Variables surface shows one scope at a time, because listing every
global under a macro's own two counters makes the macro look like it has fifteen.

References are stored as **ids**, so renaming a variable is free in both scopes and
never reaches into another macro's file. Every log line and every trigger payload
resolves the id back to the name before printing it.

## Types govern wiring, not storage

What is stored is flat text. The declared type governs what may be **connected**:

- **Read Variable** hands back a typed value, so a counter drops straight into a
  numeric port with no Convert in the wire;
- **Set Variable**'s `value` port is type-checked, so a number wired at a text variable
  is a refused drop.

Lists are unchanged: **Add to list** stores the array's JSON text and **Read from JSON**
reads it back.

## Constants, and initial values

A **constant** refuses writes and is never stored at all, so it cannot drift from its
own declaration. Use one for a threshold or an address that several macros share.

**Initial values seed on first read**, not when the macro is armed. Seeding eagerly
would fire *when a variable changes* for every declared variable on every edit that
re-arms.

Writing an unchanged value is a deliberate no-op — *"when this changes"* must not mean
*"whenever anyone looked"*.

## What can go wrong

A node pointing at a variable that was never chosen, or has since been deleted, is a
**warning** and blocks nothing. The node degrades exactly as it already does: it logs
that it stored nothing, and carries on.

That is deliberately gentler than the stance a broken *data wire* gets, where falling
back would substitute a form value for a wire you can see on the canvas. Here there is
no wire and nothing is substituted.
