---
title: Nodes, ports and wires
description: The four kinds of node, the two kinds of wire, and how a card knows what to show.
sidebar:
  order: 1
---

A macro is a graph. Nodes are the boxes; wires connect their ports. There are **four
kinds of node** and **two kinds of wire**, and almost everything else follows from
those two facts.

## The four kinds

| Kind | What it is | Exec ports | Data ports |
| --- | --- | --- | --- |
| **Trigger** | An event source. Starts a run. | One output | Usually one struct output |
| **Action** | Does something. | In, and one or more outs | Any |
| **Value** | A pure leaf read — "what is it right now?" | **None** | Exactly one output |
| **Transform** | A pure function of its inputs. | **None** | One or more in, exactly one out |

Ottomatic ships **175** of them: 55 triggers, 80 actions, 29 values and 11 transforms.
Every one has a page under [Node reference](/docs/reference/nodes/trigger/manual/manual/).

## The two kinds of wire

An **execution wire** says *what runs next*. It is drawn between the triangular
sockets, and following one is what a run actually does.

A **data wire** says *where a value comes from*. It is drawn between the round handles,
coloured by type, and it does not imply any ordering at all.

That distinction is the reason values and transforms have no execution ports. They are
never pulsed; they are **pulled** — read at the moment the node that consumes them
needs them. See [Values and transforms](/docs/concepts/values-and-transforms/).

## Ports change with what you wire

Some nodes' ports are fixed. Others resolve from configuration or from the graph:

- **Break Struct** grows one output per field of whatever struct is wired into it.
- **Convert** and **Read from JSON** take their output type from their config — or from
  the port they feed.
- **Run Script** declares its own named input and output ports, one per line.
- **Read Variable** takes the type of the variable it points at.

Node reference pages head that table **Declared ports** for this reason: for around
twenty nodes what you actually get on the card is resolved from the graph, and the
export can only see what was declared.

## Which config fields become ports

A config field only becomes a data input if it is *wired*.

- A field marked as wirable has a **socket toggle** beside its form row. It stays a
  plain form field until you tap it. That is deliberate — a card with twelve permanent
  sockets is unreadable, and most fields are typed once and never fed.
- A data input with **no form row behind it** — Run Script's named inputs, Break
  Struct's `struct`, Convert's `value` — is always shown, because there is no form row
  to opt in from.

## Identifiers are chosen

A node's config never holds an identifier you are expected to type. A geofence place, a
variable, a sound, an app's package name, another macro, a smart-home light, an NFC
tag, a Home Assistant entity — all of them come from a chooser.

The failure being avoided is always the same one: **a mistyped identifier does not fail
loudly.** It names something else, or nothing, and the node just looks broken.

Four things people genuinely *do* type get an editable field with a chooser beside it
instead — a phone number, a time of day, a Wi-Fi network name and a person's name. What
separates them is not that they are easier to type but that they are **not opaque**: a
mistyped SSID is something you can read back and see is wrong, where a mistyped id is
indistinguishable from a correct one.

A Wi-Fi network makes the case most plainly. "When I connect to my office Wi-Fi" is set
up at home, where the office network cannot be scanned — a read-only chooser would make
the commonest case unreachable. The scan is a *suggestion*; the answer set is every
network that exists.

## What a node needs

A node declares what it wants from the phone, and three different surfaces read that
one declaration:

- the **node's own card** and config sheet, with a Grant button where one is possible;
- the **Problems panel**, as a warning that blocks nothing;
- the **Permissions screen**, grouped so that seven nodes wanting overlay access are
  one row naming seven nodes.

Hardware the phone does not have is a separate axis and is deliberately *not* on the
Permissions screen: a permission is something you can go and fix, a capability is a
fact about the phone. See [Permissions](/docs/system/permissions/).
