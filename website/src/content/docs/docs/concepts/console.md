---
title: The console
description: What a run did — levels, filters, and how much of a value gets recorded.
sidebar:
  order: 7
---

Every macro has a console, the second item of the editor's bottom bar. It is the only
place in the app where a `console.log`, a failed action or an unreadable JSON path is
visible.

A run started by the engine in the background shows up in the editor's console with no
extra step — the same store serves both.

## The four levels

| Level | What lands there |
| --- | --- |
| **Debug** | The trace: every value read, every transform, a `→ node` line per node that ran, and the `in` / `out` lines carrying the data that crossed each wire |
| **Info** | What a node meant to say — a *Log Message*, a variable write, a script's `console.log` |
| **Warn** | Degraded. It worked, but not as asked |
| **Error** | Broken |

The console **defaults to Info**, with an *All / Info / Problems* filter. Debug is kept
regardless, so widening the filter reveals history rather than demanding another run.

Those `→ node` lines are what make a macro silently stopping at an *If* diagnosable,
and the `in` / `out` lines turn "which nodes ran" into "why did this come out": a wire
that silently carried nothing, a number that arrived as text, a JSON path that matched
the wrong field.

Only **wired** data appears there. A value typed into a form is already on the card.

## How much of a value you get

Values are rendered exactly as they would appear in a notification, and a value is
shown **whole** whenever the line has room for it.

A line has a 2 000-character budget, shared out by water-filling: the port names and
separators come off the top, then every value that fits under its share gives the
remainder back. So an SMS struct spends the budget on the body rather than splitting it
three ways with a sender and a timestamp that need thirty characters between them.

## Rows, and the entry behind them

A row is a **summary** — clamped to three lines. Tapping it opens the whole entry, in a
selectable block, with:

- a **copy** button;
- a **go to the node** button, where the entry has one;
- the **run id**, which is the only thing that untangles two interleaved runs.

Day separators say "Today" and "Yesterday" while you scroll; each row also carries its
own date, so a row read alone or screenshotted into a bug report still says when it
happened.

## Limits

- **500 entries per macro**, and 2 000 characters per entry.
- **Info and above are persisted** to disk; debug stays in memory, because its value is
  the live edit-and-run loop where the process is alive by definition.
- Deleting a macro deletes its log.

**Clear console** is in the console's own top bar.
