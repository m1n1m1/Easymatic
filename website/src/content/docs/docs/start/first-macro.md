---
title: Your first macro
description: Build, wire, run and arm a working macro from an empty canvas.
sidebar:
  order: 2
---

We are going to build this: **when the battery falls below 20 %, post a notification
that says what the level actually is.** It takes four nodes and teaches everything the
rest of the documentation builds on — execution wires, data wires, a struct, and the
difference between running a macro and arming it.

## 1. A new macro

On the **Workflows** tab, tap **+**. You land on an empty canvas with a bottom bar
(Problems, Console, Variables) and an **Add node** button.

Give the macro a name in the top bar — it is what the workflow list, the widgets and
the run log will call it.

## 2. The trigger

Tap **Add node** and search for `battery`. Pick **Battery Level** (a trigger in the
*Power & Battery* group). Its card appears on the canvas with:

- an **execution output** on the right, the white triangular socket labelled *Then*;
- a **data output** called `state`, carrying a struct — `isCharging`, `level`,
  `plugged`, `event`, `timestamp`.

Tap the card to open its config sheet and set **Direction** to `Below` and **Level** to
`20`. Leave the poll interval at 15 minutes.

:::note
A trigger is the only kind of node that starts a chain. Every macro needs at least one,
and a macro can have several — each starts its own independent run.
:::

## 3. Split the struct

The trigger hands you one struct, and you want one field out of it. Add a **Break
Struct** node and drag from the trigger's *Then* socket to Break Struct's *in* socket.
That is an **execution wire**: it says *what runs next*.

Now drag from the trigger's `state` output to Break Struct's `struct` input. That is a
**data wire**: it says *where a value comes from*. The moment the struct is connected,
Break Struct grows one output port per field — including `level`.

## 4. Build the text

Add a **Build text** node (a transform, in *Data*). Set its **Template** to:

```
Battery is {A}%
```

Wire Break Struct's `level` output into Build text's `a` input.

The drop is refused if the types do not match — but here they do not have to. `level`
is a whole number and `a` takes text, so Ottomatic drops a **Convert** node into the
wire for you and shows it on the canvas. That is
[autocast](/docs/concepts/types/#autocast): the conversion is a node you can see, retype
or delete, never something that happens invisibly.

Notice that Build text has **no execution ports**. It is a
[transform](/docs/concepts/values-and-transforms/) — a pure function that is *pulled*
by whatever reads it, not pulsed in sequence.

## 5. The notification

Add **Show Notification**. Wire Break Struct's *out* execution socket to its *in*.

Open its config and set **Title** to `Battery low`. The **Text** field has a small
socket toggle beside it — tap that, and the field turns into a data input port on the
card. Wire Build text's `text` output into it.

That toggle is what `@Wired` means throughout the documentation: a config field can
either be typed in, or fed by the graph, and you choose per field.

## 6. Run it by hand

You cannot tap a battery trigger to fire it. Instead, open the **Console** in the
bottom bar and then either:

- add a **Manual Trigger** node and wire its *Then* into Break Struct — its card carries
  its own **Run** button; or
- just switch the macro on and wait for the battery to actually cross 20 %.

For a first pass, the manual trigger is the faster loop. Every run writes to that
macro's console: the `→ node` line for each node that ran, and the values that crossed
each wire. If nothing happens, the console is the first place to look.

## 7. Arm it

Go back to the workflow list and flip the macro's switch on. That is **arming**: the
engine registers the battery poll, the foreground service starts, and the macro is live
until you switch it off — including across a reboot.

:::caution
Arming an invalid macro is deliberately allowed. A problem quarantines only what is
actually broken — one bad wire blocks the node that reads it, and everything else in the
graph keeps working. See [How a macro runs](/docs/concepts/running/).
:::

## What to read next

- [Nodes, ports and wires](/docs/concepts/nodes-and-ports/) — the vocabulary, properly
- [The editor](/docs/start/editor/) — everything else on that screen
- [Node reference](/docs/reference/nodes/trigger/power-battery/battery_level/) — every
  node's ports, config and requirements
