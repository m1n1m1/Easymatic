---
title: Home Assistant
description: Connecting with a long-lived token, and the four nodes it brings.
sidebar:
  order: 3
---

Home Assistant is a hub like a Hue bridge, added from the same screen — but it brings
more with it, because it **pushes**. Every state change arrives over a websocket, which
is what makes a trigger and a value node possible where Hue has neither.

## Connecting

1. **Setup → Smart home → Add a hub → Home Assistant**.
2. Ottomatic looks for instances on your Wi-Fi; otherwise type the address, including
   the scheme and port — `http://homeassistant.local:8123`.
3. Create a **long-lived access token** in Home Assistant: your profile page, at the
   bottom, *Long-lived access tokens → Create token*. The screen has a button that
   opens that page and a **Paste** button beside the field.
4. **Test**, then name the hub.

An unencrypted address is normal on your own network, and the screen says so. Over the
internet it would send your token in the clear.

:::note
Signing in with OAuth is built and switched off. Home Assistant implements IndieAuth,
where the client id *is a URL the server fetches during authorization* — so it needs a
page somebody hosts, and needs your instance to reach the internet, which an air-gapped
install never can. Home Assistant's own documentation points third-party apps at a
token, which is why that is the path offered.
:::

## What you get

**Lights, areas and scenes drive the same three light nodes** as a Hue bridge —
*Control light*, *Recall scene*, *Read light state* — with nothing special to
configure. That is what those nodes' generality was for.

Beyond that:

| Node | What it does |
| --- | --- |
| **Home Assistant state** (value) | An entity's current state, read from a locally-cached copy |
| **When an entity changes** (trigger) | One of Home Assistant's *own* triggers on an entity |
| **When an event fires** (trigger) | A named event on the bus |
| **Call a service** (action) | Any service, on any target |

### Why the value node is allowed to exist

Reading a Hue light is a network round trip, which a pulled value may not be. Reading a
Home Assistant entity is a **map lookup** — the websocket pushes every change into a
local cache, seeded with a full state read the moment it connects.

The bar was never "must not concern the network"; it was **cheap, and cannot fail**.

Like *Read Variable*, this node is not offered in *If*'s on-demand source dropdown: an
on-demand read is performed with no config, and this node's answer depends entirely on
which entity was chosen. Wire it into the `source` port instead.

## The entity trigger

**When an entity changes** does not filter the state stream. It asks Home Assistant
what triggers *that entity* offers and subscribes to the one you pick — the same
command Home Assistant's own automation editor sends.

That matters because what Home Assistant offers for a media player is
`started_playing`, `paused_playing`, `muted` and `volume_crossed_threshold` — a list no
reading of the state API can produce. Half of it is not about state at all.

Three consequences:

- **Blank means "whenever it changes"** — the one built-in row, served by the state
  stream the cache already needs. It is there because an entity that declares no
  triggers, or an instance too old for the trigger platform, would otherwise leave the
  picker empty and the node unconfigurable.
- **Only that built-in row filters.** A named trigger was already evaluated by the
  server; re-filtering here would silently drop things like `volume_changed`, which
  report no state transition at all.
- **"For at least"** is sent only when you set it, because a trigger that declares no
  such option refuses the whole subscription rather than ignoring it.

## Services

**Call a service** is the one place a **hub is its own config field**, because nothing
else determines it: an entity picker already knows its hub, but "which Home Assistant"
is a real question when the service has no target yet.

The service list is narrowed to what your target actually supports, and some services
grow generated fields from their own definitions. That needs the websocket — a snapshot
taken over the REST API alone carries neither targets nor field definitions, so it
narrows nothing and generates nothing. That is degradation, not a failure.

**Event type** on the event trigger is the one Home Assistant field you **type** rather
than choose, and the exception is forced: Home Assistant publishes no way to list event
types, so a chooser could only offer types already seen — which excludes the one you
are setting the node up for.

## Three things that fail silently

Each has a test named after it, and each looks like a broken macro rather than a unit
bug:

- **Transition is in seconds here** where the light nodes speak milliseconds. Passed
  through, a 400 ms fade becomes a six-minute one, which reads as the light never
  changing.
- **Brightness is 0–255** where the nodes speak percent.
- Home Assistant answers `{"success": true}` to a service call it carried out **against
  nothing at all**.

## The connection's lifetime

The websocket is held for as long as the engine runs, not refcounted against armed
triggers — because it also keeps the cache the value node reads. Tying it to armed
triggers would make that node answer nothing in every macro that has no Home Assistant
trigger in it, which is most of them.

Areas and entities are refreshed from the hub detail screen and whenever a picker
opens.
