---
title: Smart home (Philips Hue)
description: Pairing a bridge, what the three light nodes do, and the certificate prompt.
sidebar:
  order: 2
---

**Setup → Smart home** holds three kinds of hub: a **Philips Hue bridge**, a
[Home Assistant](/docs/integrations/home-assistant/) instance, and an
[MQTT broker](/docs/integrations/mqtt/). This page is about the first.

## Pairing a bridge

1. **Setup → Smart home → Add a hub → Philips Hue**.
2. Easymatic looks for bridges on your Wi-Fi. If none appears, type the bridge's
   address — discovery uses mDNS, which AP isolation, guest VLANs and many mesh routers
   block, and those are exactly the networks hardest to debug.
3. **Press the round link button on top of the bridge** and tap Connect. You have a
   few seconds; "the button wasn't pressed in time" just means try again.
4. Name the bridge. That name is what your macros will show.

A bridge issues its key exactly once, so a successful pairing is stored *before* the
naming step. Walking away from the form never throws it away.

### The certificate prompt

A Hue bridge presents a certificate signed by a root no Android device trusts, at a
bare LAN address that moves with your DHCP lease. Easymatic therefore pins the exact
certificate it saw during pairing, and cross-checks the bridge id it reads back against
the one mDNS advertised.

When the bridge's firmware rotates its certificate, the hub detail screen offers
**Trust new certificate**, showing both fingerprints. The wording there is the whole
advice:

> Only trust it if you reset or replaced your bridge. If you did not, something else on
> your network is answering as it.

## The three light nodes

| Node | What it does |
| --- | --- |
| **Control light** | On, off, toggle, brightness, colour, colour temperature |
| **Recall scene** | Recall, or switch off / toggle the room behind a scene |
| **Read light state** | Whether it is on, its brightness, its colour |

The nodes say **light**, not Hue: Home Assistant's lights, areas and scenes drive the
same three nodes.

A **light target** is one field, not two. Choosing "Kitchen ceiling" already determines
which bridge it is on, so there is no separate hub field — the name is cached inside the
chosen value, which is what lets the config form still read "Kitchen ceiling" with the
bridge unplugged.

### Two behaviours worth knowing

**Setting a value also switches the light on**, by default. That is right for "dim the
lamp to 30 %" and wrong for "warm the living room down for the evening", which should
not light four lamps nobody had switched on — so the value-setting operations offer
**Only lights already on**.

That option cannot be one request to the bridge, so Easymatic reads the states, then
writes **one light at a time**, paced. It is also the only path that can report
*nothing changed* with no error: the hub was reached and had nothing lit to change.
That is logged as information, not as a warning — a macro that dims the living room
every evening should not file a warning on the evenings it is already dark.

**A scene has no "off" of its own.** A scene is a saved arrangement, so recalling it is
a real operation and un-recalling it is not. *Switch off* and half of *toggle*
therefore act on the **room or zone behind the scene**, found from the scene rather
than asked for. So **off is wider than on**: recalling lights the lamps the scene
names, switching off takes the whole room — including a lamp the scene never touched,
which is both what a person means and the only thing a bridge can be asked for.

*Toggle* costs an extra read, which makes it the one scene operation that can race a
wall switch.

## Refreshing

The hub detail screen lists lights, rooms, zones and scenes, with a **Refresh**. That
snapshot is what the pickers offer, and it is refreshed after pairing, when the screen
opens, on an explicit Refresh, and when a picker opens — **never on an execution
path**. A node writes to the id it was handed, with no lookups.

Commands to one bridge are serialised and paced, because a Hue bridge silently drops
beyond roughly ten light commands a second. Without that, a loop over twenty lights
would light twelve and report success for all twenty.

## Why there is no "light changed" trigger

A Hue bridge has no push channel Easymatic uses, so every read is a round trip — slow
and failable, which is what a pulled value may not be. That is also why **Read light
state** is an *action* rather than a value node.

Home Assistant does push, which is why it has triggers and a value node. See
[Home Assistant](/docs/integrations/home-assistant/).
