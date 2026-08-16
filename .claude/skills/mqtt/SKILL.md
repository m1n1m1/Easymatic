---
name: mqtt
description: Read before touching MQTT - action.mqtt_publish, trigger.mqtt_message, value.mqtt_topic, MqttConnections, MqttSession, MqttTopics, MqttCatalog and MqttAddress.
---

# MQTT

### MQTT

The **third hub kind, and the first that is not a vendor** — which is the thing to
understand before reading anything else here. MQTT is a *transport*. A broker knows
nothing about lights: what a light looks like on one is a convention belonging to
whatever publishes it, Zigbee2MQTT's `exposes` or Home Assistant's discovery topics or
Tasmota's. So `SmartHomeKind.MQTT` has **no `SmartHomeVendor` at all**, the three light
nodes are not offered a broker, and turning a lamp on is `action.mqtt_publish` with the
topic that lamp's own software documents.

**Ottomatic is a client and never a broker.** Nothing listens for connections; every
member of the `Mqtt` facade (`core/service/`) is something said to, or heard from, a
broker somebody else runs. That is stated on the facade itself because "MQTT support" is
ambiguous everywhere else it is written, and the ambiguity is expensive — a broker is a
service with a port to open, a retained-message store to keep and an auth model to get
wrong, and none of that is in this app.

**It is a hub anyway**, rather than a library of its own, and that is where the enum
earns its keep a second time: the sealed credential, the repository, the hub list, the
detail screen, the connection-status row, `SmartHomeHubs` hydration and the validator's
"this points at a hub that is gone" all applied unchanged. What a broker brings is the
**event half alone, generalised** — a connection, a warm cache, a `TriggerSource` and
three nodes. Home Assistant's lesson was *budget for the events, not the lights*; this
is that lesson with the lights removed entirely.

**Three nodes**: `action.mqtt_publish`, `trigger.mqtt_message` and `value.mqtt_topic`.
The publish node is the whole control half, because a broker offers a publisher exactly
one operation — and its topic and message are both `@Wired`, which is what keeps that
from being a limitation: `action.for_each` over rooms with `transform.text` building
`zigbee2mqtt/{A}/set` is the macro somebody would otherwise want twenty nodes for.

**The client library is Paho's Java client, taken on the websocket's argument rather
than `HueTransport`'s.** MQTT is not a request shape over a socket, it is a framing —
variable-length remaining-length integers, packet identifiers, the QoS 1 and 2
acknowledgement flows, `PINGREQ` keepalive, session state, reconnect backoff.
Hand-rolled, its bugs surface as a connection that stays open and silently stops
delivering, which is precisely the failure a trigger cannot report. It is the *Java*
client and not `paho.android.service`: that wrapper is deprecated and exists to own a
background service, which this app already has one of, so what is taken is 250 KB of
protocol with **no transitive dependencies at all**.

**Nothing is subscribed speculatively, and that is the design's centre.** Home
Assistant's socket seeds itself with `get_states`, so its cache holds every entity from
the moment it connects. A broker has no such command — the only way to learn a topic's
value is to be *subscribed* when it is published — so a cache that covered everything
would mean subscribing to `#` and receiving every message in the house, including the
broker's own `$SYS` statistics several times a second, to serve the handful of topics a
macro names. So `MqttConnections` subscribes to exactly what something asked for: one
per armed trigger's filter (refcounted, so ten triggers on one filter cost one
subscription) and one per topic a value node has ever read.

**A value read therefore subscribes, and the first one waits.** `value.mqtt_topic` takes
the subscription out on the first read of a topic and waits briefly, because brokers
deliver **retained** messages immediately on subscribe and state topics are retained by
convention and by every major publisher. So the first read of a live topic answers and
every read after it is the map lookup the pull side requires. A topic with no retained
message answers null until something publishes to it — not a degradation but the literal
truth: until then nothing anywhere knows that value.

**Subscriptions are re-sent on every reconnect, by us, and this is the one thing here
that fails silently if it is wrong.** Paho reconnects on its own and does *not* restore
subscriptions on a clean session, so a connection that drops for a moment comes back
looking entirely healthy — connected, no error anywhere — and never delivers another
message. `MqttSession.Events.onReady` fires on the first connect and every reconnect
alike, deliberately not distinguishing them: a path that runs only after a failure is a
path that is broken.

**The session is clean and the client id is stable**, which pull in opposite directions
and are both deliberate. A stable id (derived from the hub id, truncated to the 23
characters the specification guarantees) means a broker's connection list names this
phone once rather than accumulating a row per app start. A clean session means the
broker queues *nothing* while the phone is away — which sounds ungenerous and is not: a
trigger fires on what happens while it is armed, and a night of held messages arriving
at breakfast would run a macro dozens of times for events long past.

`MqttTopics` is the filter matcher, pure and JVM-tested, and it is **ours rather than the
broker's** because one connection is shared: a message arrives because *somebody*
subscribed to a filter it matches, so deciding which nodes wanted it is this app's
question. Three rules are pinned because each fails silently. `#` matches **zero or more**
levels, so `zigbee2mqtt/#` hears the bare `zigbee2mqtt` topic too. `+` matches exactly
one, including an **empty** one. And a leading `#` or `+` must **not** reach a
`$`-prefixed topic — without that carve-out a macro watching `#` is woken several times a
second by the broker's own statistics, which reads as the trigger being broken rather
than as a rule of the protocol.

**`trigger.mqtt_message`'s `includeRetained` defaults to off**, and it is the default most
likely to be mistaken for a bug in either direction. A broker hands over every retained
message the instant a subscription is accepted, so with it on the macro fires at every arm
— at boot, after a re-enable, after every edit — carrying a value that may be months old.
It is offered rather than suppressed because there is one real use: "if the door was left
open overnight, tell me at boot".

**A wildcard topic is refused before the network**, which is the one refusal in the
publish node worth stating: `home/+/set` reads like a way to address every room at once,
MQTT has no such thing, and a broker's answer to an illegal publish is to **drop the
connection** — taking every other macro's triggers down with it seconds later, with
nothing connecting the two events.

**The topic fields are `@Suggested`, never `@Picker`**, and this is the clearest case that
mechanism has. A broker publishes no directory of its topics, so `MqttCatalog` (the sixth
hydrated registry) holds whatever spoke during the few seconds a Refresh listened —
genuinely useful, since real topics are long and opaque and copied wrong, and
*structurally* incomplete, since a device unplugged at Refresh publishes nothing. That is
exactly the middle ground between a complete chooser and a bare text box. It is also why
Refresh on a broker **listens** rather than reads, and why its button says so.

`MqttAddress` (`domain/model/`) is the **third** URL reader after `WebUrl` and
`AiBaseUrl`, and the three disagree about a scheme-less address on purpose — which is why
none is reused. `WebUrl` adds `https` because a browser bar does; `AiBaseUrl` refuses
because both schemes are ordinary for a model server; this one **adds `mqtt` on 1883**,
because that is not a guess: a home broker is a Mosquitto on its default plaintext port,
TLS is the exception rather than one of two equal options, and the ports differ anyway. It
also translates the user's spelling into Paho's (`mqtt`→`tcp`, `mqtts`→`ssl`), refuses an
`http://` address rather than reading it as a hostname — pasting the broker's *dashboard*
URL is the likeliest mistake this field will see — and refuses an **unbracketed IPv6
literal**, since `::1` is all colons and the last-colon rule would read it as the host `:`
on port 1.

Authentication is a username and password, both optional, because **anonymous is the
ordinary case** on a home network. That is why `SmartHomeHub.requiresSecret` exists: a
blank credential means a lost key on a bridge or an instance and means *anonymous* here,
so `needsPairing` had to stop reading a blank secret as breakage. The username is
deliberately **not sealed** — it is the half the user must be able to read back when a
connection is refused, which is `certSha256`'s argument in a second setting.

**No mDNS discovery**, and the absence is the defensible part: `_mqtt._tcp` is registered
but almost nothing publishes it — not Mosquitto by default, not Home Assistant's add-on,
not EMQX — so a browse would nearly always find nothing, which reads as "no broker here"
rather than as "this protocol does not announce itself".

