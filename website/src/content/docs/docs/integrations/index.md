---
title: Integrations overview
description: What lives on the Setup tab, and what each thing costs to set up.
sidebar:
  order: 0
---

Anything shared by more than one macro is configured once, on the **Setup** tab, and
referred to from nodes by a chooser. Nothing here is required to write macros — a node
that needs something you have not configured says so on its own card.

## Connections

Things outside the phone. Each can fail in ways you have to go and fix, so each has a
detail screen with a status line and a way to retry.

| | What it gets you | What it needs |
| --- | --- | --- |
| [AI](/docs/integrations/ai/) | *Ask AI*, *Describe a picture*, and the graph assistant | An API key, or a server you run |
| [Smart home](/docs/integrations/smart-home/) | Philips Hue lights, rooms and scenes | A bridge on your Wi-Fi, and its link button pressed |
| [Home Assistant](/docs/integrations/home-assistant/) | Every entity, service, area and scene it knows about, plus push triggers | Its address and a long-lived token |
| [MQTT](/docs/integrations/mqtt/) | Publish, subscribe, and read retained topics | A broker you already run |
| [Mail](/docs/integrations/mail/) | Send mail, read mailboxes, act on messages | An address and an **app password** |

Smart home, Home Assistant and MQTT are three kinds of *hub* on one screen. Add them
all from **Setup → Smart home**.

## Libraries

Your own records. Macros refer to these by id, so renaming one is free and reaches
every macro at once.

| | What it is |
| --- | --- |
| [Global variables](/docs/concepts/variables/) | Values every macro can share |
| [Places](/docs/integrations/places/) | Geofences your macros can react to |
| [NFC tags](/docs/integrations/nfc/) | Tags you have scanned and named |
| [Folder access](/docs/integrations/files/) | Folders and files the app may open |

## System

What the phone and other apps allow. Easymatic only reports these.

| | What it is |
| --- | --- |
| [Permissions](/docs/system/permissions/) | Every grant the app can want, and which nodes need it |
| [Plugins](/docs/extend/plugins/) | Other apps that add nodes to the palette |
| [App access](/docs/extend/process-api/) | Other apps allowed to run your macros |

## Integrations with nothing to configure

Several things people expect to set up need no setup at all, because they run over a
channel the phone already gives every app:

- **Messengers** — WhatsApp, Signal, Telegram and anything else. One permission
  (notification access) and no per-app configuration ever. See
  [Messengers and notifications](/docs/integrations/messengers/).
- **Calendar** — Google, Exchange, CalDAV and local calendars all work through the
  phone's own calendar provider. No OAuth, no account to add. See
  [Calendar](/docs/integrations/calendar/).
- **Photos** — the phone's media store. See [Photos](/docs/integrations/photos/).
- **SMS, calls, Bluetooth, Wi-Fi, sensors** — permissions only.
