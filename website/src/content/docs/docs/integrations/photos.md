---
title: Photos and screenshots
description: The picture nodes, the consent dialogs Android forces, and what a screenshot needs.
sidebar:
  order: 11
---

Pictures go through the phone's **media collection** rather than through a folder grant,
which is why they are a separate subject from [files](/docs/integrations/files/). A
folder grant knows a *directory*; the media collection knows a **collection** — which is
the only thing that can answer "the newest photo", "everything in Screenshots" or
"pictures taken last Tuesday".

## The nodes

| Node | Kind | Needs |
| --- | --- | --- |
| **Picture Saved** | Trigger | Photo access |
| **Screenshot Taken** | Trigger | Photo access |
| **Latest picture** / **Latest screenshot** | Value | Photo access |
| **Find Pictures** | Action | Photo access |
| **Picture Details** | Action | Photo access; location access to read where it was taken |
| **Edit Picture** | Action | Photo access — resize, rotate, mirror, crop, re-encode |
| **Change Picture Details** | Action | Photo access, permission to change your photos, overlay |
| **Move or Copy Picture** | Action | as above |
| **Delete Picture** | Action | as above |
| **Take Photo** | Action | Camera access |
| **Take Screenshot** | Action | Accessibility access |

There is **one "Picture" field**, filled from a chooser or from an upstream port.
Whether the value behind it is a path or a media reference is never a question you are
asked — but it is why a picture can be handed straight to *Describe a picture* or to a
file node.

## The three mutating nodes need more

**Change Picture Details**, **Move or Copy Picture** and **Delete Picture** modify rows
that belong to *other* apps, and Android insists the user approve each one.

That approval is a system dialog, which needs a screen — and Easymatic's engine is a
background service. So those three also declare **permission to draw over other apps**,
which is what allows the dialog to be raised from the background at all.

Three outcomes are kept apart, and it matters:

- **changed** — it worked;
- **refused** — the user said no. Not worth retrying;
- **could not ask** — nobody was there to answer. Worth retrying when the phone is in
  hand.

Folding the last into a plain error would make a locked screen indistinguishable from a
decision.

**Edit Picture** is free of all of this, because it writes a *new* file that Easymatic
owns.

## Taking a screenshot

**Take Screenshot** rides the **accessibility service**, not screen projection. That is
what makes it usable from a background macro: screen projection raises a consent dialog
every time and cannot be started headlessly.

Turn Easymatic's accessibility service on in Settings — the node's card has a button
that takes you there.

## The picture trigger and the first arm

**Picture Saved** does not replay your camera roll when you first arm it. A macro armed
on a phone with four thousand photos must not run four thousand times, so the first arm
records where the collection is and fires nothing.

That mark is deliberately **not cleared when you disarm**, because a disarm and a re-arm
are indistinguishable from the trigger's point of view.

One behaviour is worth knowing because it explains a delay: from Android 10 a camera app
inserts its row *first*, invisible, then fills in the bytes and publishes. The trigger
looks back a few seconds precisely so the picture everybody actually wanted is not
skipped.

## Large pictures

Editing a picture decodes it into memory. Very large images are downsampled rather than
crashing the process, so a re-encode of a 108-megapixel photo produces a smaller result
than you might expect — the alternative is no result at all.
