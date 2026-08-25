---
title: The editor
description: The canvas, the palette, the node card, the bottom bar and the AI assistant.
sidebar:
  order: 3
---

The editor is one screen: a canvas with a bar at the top, a bar at the bottom, and two
floating buttons.

## The canvas

| Gesture | On empty canvas | On a node card |
| --- | --- | --- |
| Tap | Select a wire, or clear the selection | Select the node |
| Drag | Pan | Move the node |
| Hold | Draw a marquee to select several | Enter multi-select |
| Two fingers | Pinch to zoom, drag to pan | Same — a pinch that starts on a card still zooms |

Zoom controls and a **Fit to screen** button sit in the corner, and the zoom level is
shown as a percentage.

A node's **ports** are the handles down its two sides. Drag from a handle to another
handle to wire them. Dragging from a port onto empty canvas opens the palette
**restricted to nodes that can connect there** — a suggestion, not a rule, with a
**Show all** escape hatch.

## The top bar, in two modes

With **nothing selected** the bar is about the workflow: back, its name, the **Enabled**
switch, and an overflow menu (rename, duplicate, delete…).

With **something selected** it is entirely about the selection: how many nodes,
configure, duplicate, delete, and a ✕ to clear.

They never share the bar. That is why the name has room to be read and why "1 node
selected" is spelled out in full.

## Adding nodes

**Add node** opens the palette. It is grouped by node kind — Triggers, Actions, Values,
Transforms — with each kind's categories collapsed underneath, and a search box at the
top that matches names *and* descriptions. Searching for `hue` finds the light nodes
even though their type ids say `light`; searching for `whatsapp` finds the messenger
nodes.

## A node card

Every card carries, left to right:

- **execution inputs** — the socket that says *run me next*;
- **data inputs** — one per wired value, coloured by type;
- the node's icon, name and a line of its own status;
- **data outputs**, then **execution outputs**.

Tap a card to open its **config sheet**. Each field is a form row; a field marked
`@Wired` has a small socket toggle beside it that turns the row into a data input port
on the card. A field that holds an identifier — a place, a variable, an app, a sound,
another macro — opens a chooser rather than accepting typed text, for the reason in
[Identifiers are chosen](/docs/concepts/nodes-and-ports/#identifiers-are-chosen).

A card also shows what it is missing: a permission the phone has not granted, hardware
the phone does not have, or a reference to something deleted. Where a permission can be
granted, a **Grant** button is on the card itself.

## The bottom bar

Three surfaces, each replacing the canvas rather than covering it.

**Problems** is what the graph *is*. Errors and warnings, each naming the node or the
wire it is about, with a badge on the bar itself so you do not have to open it to know
there is something there. It is not a log — see [Validity](/docs/concepts/running/).

**Console** is what a run *did*. Every node that ran, every value that crossed a wire,
every `console.log` from a script, every failure. Filterable by All / Info / Problems,
and a row taps through to the whole entry with a copy button and a "go to the node"
link. See [The console](/docs/concepts/console/).

**Variables** is what the state *is right now* — this workflow's declarations with
their live values, and a row through to the shared globals.

## Running by hand

There is no whole-graph Run button, deliberately. A **Manual Trigger** node carries its
own **Run** button on its card — which becomes **Stop** while that run is going — so a
macro full of other triggers can still be started by hand, and an *armed* macro can be
run without disarming it.

The same path serves the home-screen widgets and launcher shortcuts — see
[Widgets and shortcuts](/docs/system/widgets/).

## The AI assistant

A small button above **Add node** opens the assistant. It is the only surface that sits
*over* the canvas rather than instead of it, because its edits land live: nodes appear
as they are made and the canvas follows whatever was just touched, until your first pan
or zoom hands control back for the rest of the turn.

The panel folds by dragging its handle — the conversation collapses, the input never
moves. ↺ starts a new conversation and leaves the graph alone; ✕ closes the assistant.

It needs an AI connection and a model profile. See [AI](/docs/integrations/ai/).
