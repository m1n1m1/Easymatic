---
title: Control flow
description: Branching, loops, lists, waiting, and stopping.
sidebar:
  order: 4
---

## Branching

**If** is the graph's only comparison and only conditional branch. It takes a source
and a value, compares them, and routes execution to `true` or `false`.

The **Source** field either reads the node's own wired `source` port, or names a value
node to read on demand — which needs no wire and no position on the canvas.

Operators are `equals`, `not equals`, `greater than`, `less than`, `greater or equal`,
`less or equal`, `contains` and `matches regex`. The comparison type is normally
`Auto`; setting it explicitly matters when you want ordering on dates rather than on
their text.

An unwired branch simply ends that pass. There is no separate "break" node because
of it.

## Loops

Three loop actions, all with the same shape: two **forward** execution outputs,
`body` and `completed`.

| Node | Driven by | Data outputs |
| --- | --- | --- |
| **Repeat for each item** | A list | `item`, `index` |
| **Repeat** | A count | `index` |
| **While** | A condition | `index` |

Wiring the end of the body back into the loop is **not** how it works. That edge is an
execution cycle, and the validator reports it as an error. The executor is what goes
round again, not the graph — including for *While*, which needs no back edge.

Each pass re-reads whatever the body pulls, so a value or transform inside a loop body
is genuinely re-read every time rather than frozen at the first pass.

*Repeat for each item* **snapshots its list when it starts**, so appending to that list
from inside the body does not extend the loop. *While* re-evaluates its condition every
pass, which is the unbounded case and where an iteration cap applies.

## Lists

Every type is also available as a list. A list port takes its **element's** colour and
is drawn with a **square handle** instead of a round one — the same way a Blueprints
pin splits "what type" from "how many".

Lists come from four places:

- **Split text** — its input is multi-line and wirable, so one node is both a *list
  literal* (type one item per line) and a *splitter* (wire an SMS body in);
- **Read from JSON** with its list switch on;
- **Run Script** with a `[]` output port;
- **Break Struct** on a struct that has a list field.

Consuming them: count, item, join, contains, index of, sort, slice — plus
**Add to list** and **Clear list**, which write into a variable.

Nothing autocasts *into* a list. Making one is a visible node's job, the same rule
structs follow.

## Waiting

**Wait** pauses for a duration and blocks its one output. Use it for "wait five
seconds".

**Wait Until** does not block, and that is the point: *"at 22:00, turn the lights off"*
is almost never the only thing a macro wants to do when it starts. So it has **two
execution outputs and both fire**, at different times:

- **Carry on now** — immediately, so the rest of the macro proceeds;
- **When the time comes** — hours later, at the moment you named.

Both mean *go*. Wiring the wrong one is the whole mistake to design against, which is
why they are labelled rather than named `out` and `resumed` on the card.

The deferred branch sees **the graph as it was at the fork**. Its data is a snapshot,
which is what makes a fork inside a loop body work: iteration 3's branch fires after
the loop has moved on, and only its own copy still carries iteration 3's index.

Wiring from the immediate branch into the deferred branch (or the other way) is an
error, because at runtime that wire would read nothing and quietly substitute the
consumer's form value.

The deferred half survives the macro being disarmed only as far as the arm that
started it. A manual run — a widget, a shortcut, the card's Run button — holds the
foreground service up until the wait is done.

## Stopping

**Stop Macro** halts the current execution chain: nothing wired after it runs.

It stops *that chain*, not the macro's other triggers and not its sibling branches.
Each node runs in isolation; a failure in one branch does not take out the others. See
[How a macro runs](/docs/concepts/running/).

## Enabling and disabling macros

**Enable Macro** and **Disable Macro** flip another macro's switch, choosing it from a
list rather than by typing an id. A reference to a macro that has since been deleted is
a warning that blocks nothing — the node reports that nothing changed and carries on.
