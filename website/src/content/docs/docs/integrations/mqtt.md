---
title: MQTT
description: Connecting to a broker you run, and the three nodes it brings.
sidebar:
  order: 4
---

MQTT is the third kind of hub on **Setup → Smart home**, and the first that is not a
vendor. A broker is a **transport**: it knows nothing about lights. What a lamp looks
like on one is a convention belonging to whatever publishes it — Zigbee2MQTT's
`exposes`, Home Assistant's discovery topics, Tasmota's.

So the light nodes are not offered a broker. Turning a lamp on is **Publish to MQTT**
with the topic that lamp's own software documents.

:::note
**Easymatic is a client and never a broker.** Nothing listens for connections. You need
a broker somebody else runs — Mosquitto, EMQX, the one inside Home Assistant, or the
one Zigbee2MQTT talks to.
:::

## Connecting

1. **Setup → Smart home → Add a hub → MQTT broker**.
2. Type the **broker address**. A bare `192.168.1.10` becomes `mqtt://192.168.1.10:1883`
   — that is not a guess: a home broker is a Mosquitto on its default plaintext port.
   Write `mqtts://…` for TLS. An IPv6 literal must be bracketed (`[::1]`).
3. **Username and password are optional.** Leave them empty if your broker allows
   anyone on the network to connect, which most home brokers do.
4. Test, then name it.

Pasting the broker's *dashboard* URL is the likeliest mistake this field will see, so an
`http://` address is refused rather than read as a hostname.

There is deliberately **no discovery**: `_mqtt._tcp` is registered but almost nothing
publishes it — not Mosquitto by default, not Home Assistant's add-on, not EMQX — so a
browse would nearly always find nothing, which reads as "no broker here" rather than as
"this protocol does not announce itself".

## The three nodes

| Node | What it does |
| --- | --- |
| **Publish to MQTT** (action) | The whole control half — a broker offers a publisher exactly one operation |
| **When a topic changes** (trigger) | Fires on messages matching a topic filter |
| **MQTT topic** (value) | The last value seen on a topic |

Publish's **topic and message are both wirable**, which is what keeps one node from
being a limitation: a *Repeat for each item* over rooms, with **Build text** making
`zigbee2mqtt/{A}/set`, is the macro you would otherwise want twenty nodes for.

**A wildcard topic is refused before it reaches the network.** `home/+/set` reads like a
way to address every room at once, MQTT has no such thing, and a broker's answer to an
illegal publish is to **drop the connection** — taking every other macro's triggers down
with it seconds later, with nothing connecting the two events.

## Subscriptions, and what that means for you

Nothing is subscribed speculatively. A broker has no "tell me everything's current
value" command, so a cache that covered everything would mean subscribing to `#` and
receiving every message in the house — including the broker's own statistics several
times a second — to serve the handful of topics a macro names.

So Easymatic subscribes to exactly what something asked for: one subscription per armed
trigger's filter (ten triggers on one filter cost one subscription), and one per topic a
value node has ever read.

**A value read therefore subscribes, and the first one waits briefly.** Brokers deliver
**retained** messages immediately on subscribe, and state topics are retained by
convention and by every major publisher — so the first read of a live topic answers and
every read after it is the map lookup a value node requires.

A topic with **no retained message answers nothing** until something publishes to it.
That is not a degradation but the literal truth: until then, nothing anywhere knows that
value.

## Retained messages on a trigger

**Include retained messages** defaults to **off**, and it is the setting most likely to
be mistaken for a bug in either direction.

A broker hands over every retained message the instant a subscription is accepted — so
with it *on*, the macro fires at every arm: at boot, after a re-enable, after every
edit, carrying a value that may be months old.

It is offered rather than suppressed because there is one real use: *"if the door was
left open overnight, tell me at boot"*.

## Topic filters

The matcher follows the specification, including three rules that fail quietly if you
assume otherwise:

- `#` matches **zero or more** levels, so `zigbee2mqtt/#` also hears the bare
  `zigbee2mqtt` topic.
- `+` matches exactly one level, **including an empty one**.
- A leading `#` or `+` does **not** reach `$`-prefixed topics. Without that carve-out a
  macro watching `#` would be woken several times a second by the broker's own
  statistics.

## Finding topics

Topic fields are **suggested, not chosen**: a broker publishes no directory of its
topics. The hub detail screen has a **Listen for topics** button that subscribes
broadly for a few seconds and records whatever spoke.

That is genuinely useful — real topics are long, opaque and copied wrong — and
structurally incomplete, since a device unplugged at that moment publishes nothing. So
the field stays typeable.

## The session

The client id is stable, derived from the hub, so your broker's connection list names
this phone once rather than accumulating a row per app start.

The session is **clean**, so the broker queues nothing while the phone is away. That
sounds ungenerous and is not: a trigger fires on what happens *while it is armed*, and a
night of held messages arriving at breakfast would run a macro dozens of times for
events long past.
