---
title: Documentation
description: How Ottomatic's node graph works, how to configure every integration, and a reference for all 175 nodes.
sidebar:
  order: 0
---

Ottomatic automates a phone by wiring nodes together. A **trigger** says when something
happens, an **action** does something about it, and **values** and **transforms** feed
them the data they need. A foreground service runs the result in the background.

## Start here

- [Install and first run](/docs/start/install/) — what to install and what to allow
- [Your first macro](/docs/start/first-macro/) — four nodes, from empty canvas to armed
- [The editor](/docs/start/editor/) — the canvas, the palette, the bottom bar

## How it works

The [core concepts](/docs/concepts/nodes-and-ports/) section is the part worth reading
once, properly:

| | |
| --- | --- |
| [Nodes, ports and wires](/docs/concepts/nodes-and-ports/) | The four node kinds, the two wire kinds, and why identifiers are chosen |
| [Types and conversion](/docs/concepts/types/) | Why a drop is refused, what autocast does, how dates behave |
| [Values and transforms](/docs/concepts/values-and-transforms/) | The pull side — read on demand, never pulsed |
| [Control flow](/docs/concepts/control-flow/) | Branching, loops, lists, waiting, stopping |
| [Variables](/docs/concepts/variables/) | The graph's only writable state |
| [How a macro runs](/docs/concepts/running/) | Arming, and what a problem actually blocks |
| [The console](/docs/concepts/console/) | What a run did |

## Setting things up

Anything shared by more than one macro is configured once on the **Setup** tab —
[overview here](/docs/integrations/).

**Connections**: [AI](/docs/integrations/ai/) ·
[Smart home](/docs/integrations/smart-home/) ·
[Home Assistant](/docs/integrations/home-assistant/) ·
[MQTT](/docs/integrations/mqtt/) · [Mail](/docs/integrations/mail/)

**Libraries and permissions-only integrations**:
[Messengers](/docs/integrations/messengers/) ·
[Calendar](/docs/integrations/calendar/) ·
[Places](/docs/integrations/places/) · [NFC tags](/docs/integrations/nfc/) ·
[Files](/docs/integrations/files/) · [Photos](/docs/integrations/photos/)

**The phone**: [Permissions](/docs/system/permissions/) ·
[Running in the background](/docs/system/background/) ·
[Widgets and shortcuts](/docs/system/widgets/)

## Extending it

- [Scripting](/docs/extend/scripting/) — JavaScript inside one node
- [The process API](/docs/extend/process-api/) — run a macro from another app or a shell
- [Writing a plugin](/docs/extend/plugins/) — add your own nodes to the palette

## Node reference

Every one of the 175 nodes ships with a page listing its ports, its configuration and
anything it needs from the phone. Those pages are **generated from the node declarations
themselves**, so they cannot describe a port a node no longer has.

Pick the group you want in the sidebar, or search — the search box covers every node name
and description.

## Stuck?

[Troubleshooting](/docs/help/troubleshooting/) · [FAQ](/docs/help/faq/)
