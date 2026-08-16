---
name: scoped-config-fields
description: Read before adding or changing a config field that narrows another - @Picker(scopedBy), @Suggested, SuggestionSource, Suggestions, keysScopedBy and ConfigField.backedBy.
---

# Scoped config fields

### Scoped config fields

A config field may name **sibling fields that narrow it**, and a second may offer *suggestions*
without restricting what can be typed. One mechanism, declared two ways, and Home Assistant is
its first heavy user rather than its shape.

`@Picker(kind, scopedBy = […], optional = …)` narrows a **read-only** chooser: an entity picker
scoped by a hub lists that hub's entities, a service picker scoped by an entity lists what that
entity accepts. `@Suggested(source, scopedBy = […])` is the **editable** half — a text field with
a dropdown — for an answer set that is *known but not closed*. That third shape is the one the
app was missing: `@Picker` is for a set that is complete, a plain field for one nothing knows,
and this is the middle, where **which of the two it is depends on a sibling's value rather than
on the declaration**. A light publishes `brightness` and `color_temp_kelvin` and the dropdown
says so; an entity that was `unavailable` at Refresh publishes almost nothing and the dropdown
is empty; the same field serves both and never changes kind.

`@MailFolder` was this idea built for one field, and it is **gone** — the three mail nodes now
declare `@Suggested(MAIL_FOLDER, scopedBy = ["accountId"])`, and mail's own tests passing
unchanged is what shows the mechanism generalised rather than merely arrived.

**Options are computed in `domain`, which is what makes it generic**, and the enabling move is
`HaCatalog` (`domain/registry/`) — the fifth hydrated registry after `MacroDirectory`,
`GlobalVariables`, `SmartHomeHubs` and `AiConnections`. The other four exist so the *validator*
can ask whether a reference resolves; this one additionally lets `effectiveConfigSchema` **narrow
a form**, which nothing in `feature/` could do: `effectiveConfigSchema` is the only thing that
decides which fields a form has, so generated fields can come from nowhere else. It carries a
projection and never the hubs, because a hub also holds a sealed credential. Each
`SuggestionSource` gets one branch in `Suggestions`' exhaustive `when` and **nothing else** — a
future integration adds a member and a resolver and touches one file.

**Empty always means "cannot narrow", never "nothing exists".** An unhydrated catalogue, a blank
scope, an old snapshot with no metadata, a socket that is down — all leave the field exactly as
it was before it was scoped. A form that silently empties its own choosers is worse than one
never narrowed, and that rule is tested at every call site.

**Not every scope can be answered from a registry, and pretending otherwise is what made the
first cut of this wrong.** `Suggestions.isLocal` is that seam: a mailbox list is an authenticated
IMAP `LIST`, and a Home Assistant *trigger* list is a websocket command about one entity — so
both are fetched by the widget and cached in a ViewModel rather than resolved in `domain`. The
*declaration* stays uniform, which is the whole point: a node author writes one annotation and
never learns which kind theirs is. What the fetching side must preserve is the rule above, in a
sharper form — **a request still in flight and a request that came back empty are different
states**, and collapsing them makes every chooser flash "nothing matches" for the length of a
round trip.

**A scoped list is not complete**, which matters because `PickerKind.HA_ENTITY`'s read-only
justification is precisely that the answer set *is* complete. So a scoped chooser always offers
**Show everything**. Without it, one wrong `target` field in somebody's custom integration makes
a service unreachable by any means.

Changing a scoping field **clears the pickers below it**, transitively (`keysScopedBy`, called
from `updateNodeConfig` beside `pruneRetypedEdges`, which is already a schema-aware follow-up to
a config edit). Only pickers: a `@Suggested` value was *typed*, so it means what somebody meant
by it — **a picker's value is only meaningful inside its scope; a typed value is meaningful
because somebody typed it.** The validator could not do this job instead: it lives in `engine/`
and could only ask whether the *hub* still exists, not whether a service is still legal beside an
entity.

**"Below it" is literal, and that is what makes a mutual scope legal rather than fatal.** Two
fields may narrow *each other* — `action.ha_service`'s entity lists what the chosen service
accepts and its service lists what the chosen entity accepts, and both are useful — but clearing
in both directions means picking either one wipes the other and the form can never hold both.
That shipped and was the first bug reported against it. So clearing runs **forwards only**, by
declaration order, which is form order: you fill a form downwards, and answering a question
re-asks the ones beneath it rather than the ones above. It also breaks any cycle by
construction, which is a stronger guarantee than the visited-set guard it replaced — that
terminated, and then cleared the wrong field.

`ConfigField.backedBy` is the last piece and the most novel: **a form field whose value lives
inside another field's structured value.** `action.ha_service` generates one row per input the
chosen service accepts, from the `selector` Home Assistant publishes for it, and those land in
the `data` property's JSON rather than under config keys of their own — they cannot be
properties, because a config class is fixed at declaration time and which fields a service takes
depends on a service chosen later. This is `@Ports`' trick (a parsed spec stored as text)
generalised from a list to a map. A generated key is `data.brightness_pct`, and splitting on the
first dot recovers both halves because a declared key is a Kotlin property name and cannot
contain one. **An unrecognised selector generates nothing** and the raw JSON box stays beneath as
the escape hatch, which is what makes this safe against a server that updates on its own
schedule.

