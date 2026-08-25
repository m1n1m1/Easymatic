---
title: Files and folders
description: Why Ottomatic declares no storage permission, and what that means for where files can live.
sidebar:
  order: 10
---

Ottomatic reads and writes files and **declares no storage permission** to do it.

That is not a loophole. The editor's chooser hands back a location Android has
*granted*, the grant is made to outlive the task and a reboot, and the engine opens it
days later from the background. The rule generalises: **choosing needs a screen; using
does not.**

## One field, not two

Every file node's location is a single **File** field. There is no "storage area"
setting and no folder picker beside every path, because that split was Android's problem
modelled as your vocabulary — you say *where a file is*; which mechanism opens it is
plumbing.

- A path with **no leading `/`** is the app's own private storage. It needs no grant at
  all, and is the right place for a macro's working files.
- Anything else goes to whichever **granted folder** covers it.
- Nothing covering it is a failure that names the path and says where to fix it.

Because nothing in a macro references a grant, **re-granting a folder repairs every
macro on the phone with nothing edited.**

The field is editable *and* has a chooser, which is forced rather than convenient: a
chooser can only offer files that exist **now**, and the point of a write is a file that
does not. "Read the file the last run wrote" names a file that is absent at config time.
The field is also wirable, because every file macro worth writing builds its path with
**Build text**.

## Folder access

**Setup → Folder access** is where grants live. Two kinds, because Android has two and
they are not interchangeable:

- **Choose a folder** grants everything inside it, and is the one to reach for.
- **Choose a file** grants exactly one, and cannot name a file that does not exist yet.

### What Android refuses

Android 11 and later **refuses a folder grant** on:

- the root of internal storage,
- the `Download` directory itself,
- memory-card roots.

Sub-folders of all three are fine, so `Download/Ottomatic` works where `Download` does
not. The chooser simply declines, which reads as the app being broken — the Folder
access screen says so first.

`Download` therefore has an escape hatch, and it is the only reason single-file grants
exist: *Choose a file* has no such restriction. Those grants are read-only by nature, so
they serve **Read file** and **File info** and never a write or a delete.

`Android/data`, `Android/obb` and every other app's private storage are unreachable by
any route.

:::caution
Persisted grants are **capped** per app, and going over does not fail — the platform
silently evicts the **oldest** grant. On a real phone that is very likely a sound URI a
*Play sound* node was using.
:::

## The six nodes

| Node | Notes |
| --- | --- |
| **Read file** | Text or bytes |
| **Write file** | Create, overwrite or append |
| **File info** | Exists, size, modified — with `exists` and `error` kept apart |
| **List files** | Glob-matched |
| **Move file** | Rename or move |
| **Delete file** | |

## Why there is no "does this file exist?" value node

Three reasons, and all three are needed:

1. **Nothing pushes.** A filesystem answers only when asked; there is no cache to keep
   warm.
2. **The answer depends entirely on config**, which is what already excludes *Read
   Variable* from *If*'s on-demand dropdown — so it could never be used there, which is
   most of why a value node is worth having.
3. **It could not answer honestly.** "There is no file", "no grant covers this" and "the
   provider failed" are three different sentences somebody needs. **File info** keeps
   them apart.

## Two things that fail quietly, handled for you

- Writing over an existing file **truncates**. Writing two bytes over a kilobyte would
  otherwise leave 1 022 stale ones, and the result would still parse as *something*.
- Creating a document through a folder grant **never overwrites** — a collision becomes
  `notes (1).txt` — and a recognised MIME type may append its own extension, turning
  `data.csv` into `data.csv.txt`. The name is read back off the created file and
  reported, so a downstream node uses the real one.

## Path safety

A path containing `..` is refused **before** normalisation, so `a/../../b` is rejected
rather than quietly resolved. Backslashes and control characters are refused too — a
wired Windows path is a realistic input, since the field can be fed by an HTTP response,
a script result or an external call.
