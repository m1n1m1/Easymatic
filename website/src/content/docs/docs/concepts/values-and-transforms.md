---
title: Values and transforms
description: The pull side of the graph — read on demand, memoized per consumer, never pulsed.
sidebar:
  order: 3
---

Half the graph runs in sequence. The other half does not.

**Values** and **transforms** have no execution ports at all. They are never pulsed;
they are **pulled** — read at the moment the node that consumes them collects its
inputs. A value node is a pure *leaf*; a transform is a pure *function* of its data
inputs.

## When a value is read

The rule is one sentence: **a value is read just before the node that uses it.**

It is memoized per consuming node, so:

- every port of one node sees a single, consistent read — two ports of the same node
  can never disagree about the battery level;
- a *second* consumer reads fresh, so a value on the far side of a five-minute Wait is
  not stale.

Pulling a transform first pulls whatever feeds it, sharing one memo across the whole
chain. A value node reaching one consumer through two transforms is still read exactly
once.

## What may be a value

A value must be **cheap** and **must not fail**. That is the whole contract, and it is
about the *transport* rather than the subject:

- A battery level is a synchronous device property. Fine.
- A Home Assistant entity's state is on the network — and is still fine, because a
  websocket pushes every change into a local cache, so the read is a map lookup.
- A Hue light's state is **not**, because a bridge has no such channel and every read
  is a round trip. That is an action's job instead.

A value may declare a permission. Where the grant is missing it answers *nothing*
rather than throwing, so the consumer falls back to its form value and a comparison
fails closed.

## Every trigger over a readable state has a value too

A trigger answers *"tell me when this changes"*. A value answers *"what is it right
now?"* They are not substitutes:

- "when it gets dark, turn the torch on" is a **trigger**;
- "when I get home, *if* it is dark, turn the torch on" is a **value** read inside an
  *If*.

A state with only the trigger half would force you to arm a second macro just to
remember what the first one saw. So the two ship together — except where there is
genuinely nothing to read:

- the trigger is an **event** with no resting value — a shake, a tap, an SMS, a boot,
  an NFC tap. There is no "which tag am I on right now";
- reading it is **expensive or failable**, which is an action's job.

The rule does not run backwards: a value that answers a real question is worth having
even where no trigger makes sense.

## There is no condition node

A condition is not a node family, it is a *comparison over a value*. So the two halves
are declared separately and combined:

**If** is the graph's only comparison and only conditional branch. It sits on the
canvas and routes execution to `true` or `false`.

Its **Source** field can take its value from two places:

- the node's own wired `source` port — drag a value or a transform into it;
- a **value node read on demand**, chosen from a dropdown, which needs no edge and no
  position on the canvas at all. Comparing a device property therefore costs nothing.

Two value nodes are excluded from that dropdown — **Read Variable** and **Home
Assistant state** — and for one shared reason: an on-demand read is performed with *no
config*, and both of those nodes' answers depend entirely on which variable or entity
was chosen. Offering them would offer a comparison that silently never matched. Wire
them into the `source` port instead, which is one drag.

## There is no way to attach a condition to a node

A per-node gate existed once and was removed. It read as hidden control flow — nothing
on the card said whether a condition was incoming or outgoing — and it duplicated what
*If* already shows visibly.

"Run this only when X" is an **If** upstream, including for triggers.

## Transforms

A transform needs at least one data input and has exactly one data output. Three
general ones cover most of what you need:

| Node | What it does |
| --- | --- |
| **Convert** | Text ↔ number ↔ yes/no ↔ date. The autocast target. |
| **Read from JSON** | One value out of JSON text, by dot path. |
| **Build text** | A template with `{A}` / `{B}` / `{C}` slots. |

The rest are list operations — count, item, join, contains, index of, sort, slice,
split text. See [Lists and loops](/docs/concepts/control-flow/#lists).
