---
title: Widgets and shortcuts
description: Running a macro from the home screen, and what the panel shows.
sidebar:
  order: 3
---

The question these answer is: **which macros can I run by hand, and can I run one now?**

## Two widgets

**Run tile** is one manual trigger as a button. Place it, pick the macro, tap it to run.

**Ottomatic panel** is everything else — engine status, problem count, last run, and a
grid of triggers — with each section switched on or off per placed widget.

Turn everything off but the triggers and the panel is a trigger deck; turn the triggers
off and it is a status card. A panel with every section off is legal and says so, because
clearing it out before choosing what to put back is a real thing to do.

The trigger grid keeps the order you picked, which is the whole point of picking.

## Launcher shortcuts

Long-press the app icon for shortcuts to your manual triggers, the same way.

## A macro's appearance

Each macro carries an **icon** and an **accent colour**, set from its overflow menu.
Those are what the workflow list, the widgets and the shortcuts all draw.

On a widget the accent tints the glyph and its chip, never the card — the surface belongs
to the system there, and a user-red card on a wallpaper-blue home screen would read as
pasted on. Widgets follow the **system** light/dark theme, unlike the app, which is
permanently dark.

Changing a macro's appearance never re-arms it.

## Every manual run is the same path

The Run button on a *Manual Trigger* card, a widget tap and a launcher shortcut all go
through one function. That is why:

- a **disabled macro still runs** from a widget or a shortcut — you can see the switch is
  off and mean it anyway;
- a *macro finished* trigger fires after a manual run that threw, the same as after any
  other;
- a macro containing a **Wait Until** holds the foreground service up until the deferred
  half is done, even though nothing is armed.

An **external** call is the one path that does require the switch to be on, because the
caller cannot see that it is off. See [the process API](/docs/extend/process-api/).
