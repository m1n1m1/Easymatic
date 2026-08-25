---
title: Types, conversion and text
description: Why a drop is refused, what autocast does about it, and how dates behave.
sidebar:
  order: 2
---

The graph is **strictly typed**. A whole-number output is not silently accepted by a
text input, and a drop that does not type-check is refused rather than coerced.

## The types

Values carry one of five primitive families plus structs and lists:

| Family | Looks like | Notes |
| --- | --- | --- |
| Text | `Kitchen` | Anything at all converts *to* text |
| Number | `21.5` | `Double` and `Float` are distinct primitives |
| Whole number | `43` | `Int` and `Long` are distinct primitives |
| Yes or no | `true` | |
| Date & time | `2026-08-24T18:00+02:00` | Its own type, its own port colour, its own picker |
| Struct | `{ level: 43, isCharging: false }` | An object with named fields |
| List | `["a", "b"]` | List-ness is an axis of its own, not a type |

Type checking is **invariant** on primitives: `Int` in, `Int` out. What is *not*
invariant is a struct, which is width-subtyped — so a port declared as "any object"
accepts every struct and nothing else.

That is what **Break Struct**'s input is. Deliberately not a wildcard: a wildcard would
let a number or a date be wired in, where the node would sprout no output ports and
look broken.

## Autocast

When a data drop fails the type check, Ottomatic asks its conversion table whether the
two types convert. If they do, it **drops a pre-configured Convert node into the wire**
and shows it on the canvas.

You see the node appear. You can retype it, delete it, or set what it does when the
conversion fails. A drop with no possible conversion is still refused.

The rule for what converts:

- **Every primitive pair converts**, including failable ones like text → number.
- **Anything at all converts to text.**
- **Nothing converts to a struct.**

Because the conversion is a node you can see — carrying its own *If it fails* field —
Convert never throws. It always produces a value of the requested type. That
permissiveness is only safe while the node stays on the canvas, which is why autocast
inserts one rather than converting invisibly.

`Number` and `Whole number` name a *family*, not a Kotlin type. When a conversion feeds
a port that is specifically `Long` or `Float`, the Convert node's output narrows to
match its consumer.

## Text

**Build text** is the template node. Its **Template** field takes `{A}`, `{B}` and
`{C}` slots filled from three inputs, which is how a bare `43` becomes
`Battery is 43%`.

Everything renders as text the same way, everywhere — in a notification, in a `Build
text` slot, in a wired config field, in the console, and in an *If* comparison. A
struct renders as compact JSON, so a struct converted to text can be fed straight back
into **Read from JSON**.

## JSON

**Read from JSON** takes a dot path with array indexing — `main.temp`,
`items.0.price`, `items[0].price` — and a declared result type. A path that does not
match lands on the node's fallback rather than halting the macro.

Wire an HTTP response's body into it, or a struct that has been converted to text.

## Dates and times

A timestamp is a **Date & time**, not a number. It is invariant against whole numbers —
bridging the two is exactly the visible job of a Convert node — and it renders as
ISO-8601 with an offset.

Parsing is deliberately lenient. All of these are accepted:

```
1756051200000        epoch milliseconds
1756051200           epoch seconds
2026-08-24T18:00Z    ISO, with or without an offset
2026-08-24           a date
18:00                today at 18:00
```

That last form is what makes "only after 18:00" expressible, and it re-resolves every
time the node is read.

Ordering comparisons parse both sides before comparing, because ISO text does not sort
chronologically across offsets. `Equals` still compares text.

**A duration is not a date and time.** A Wait node's duration, a poll interval and a
schedule's `elapsedMs` stay plain numbers, and a *time of day* stays an `HH:mm` string
with a clock face rather than a calendar. `25:99` is clamped to `23:59` rather than
being read as 1 599 minutes past midnight, which is not a time.

**Current time** is the only source of a moment that needs no trigger. Every other one
arrives as a field of a trigger's struct.
