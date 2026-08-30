---
title: Extending Easymatic
description: Three ways to go past the palette — a script, another app calling in, and a plugin adding nodes.
sidebar:
  order: 0
---

There are three doors out of the palette, and which one you want depends on which
direction the work is going.

## Scripting — inside one node

**Run Script** runs JavaScript over inputs you name and returns values on ports you
name. It is for what a palette never can cover: arithmetic over two readings, pulling a
code out of an SMS, reshaping an API response.

No new app, no installation. See [Scripting](/docs/extend/scripting/).

## The process API — another app runs your macros

A macro carrying a **Called by Another App** trigger can be started from an Android app,
a shell script, Tasker, Automate, MacroDroid, `adb` or Termux. It takes typed inputs and
answers what happened.

Nothing about it needs a library or a dependency on Easymatic. See
[The process API](/docs/extend/process-api/).

## Plugins — new nodes in the palette

A plugin is a **separate Android app** that adds trigger, action, value and transform
nodes. It runs its own code, in its own process, under its own manifest permissions.
Easymatic never shares its permissions with it.

Your node appears in the palette, wires up like any other, and is configured with the
same form widgets. See [Writing a plugin](/docs/extend/plugins/).

## Which one

| You want to… | Use |
| --- | --- |
| Compute something no node does | **Run Script** |
| Trigger a macro from your own app or a shell | **The process API** |
| Add a reusable node other people can place | **A plugin** |
| Talk to a service Easymatic does not know | **A plugin**, or *HTTP Request* plus *Read from JSON* |
