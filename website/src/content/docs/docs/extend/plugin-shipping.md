---
title: Shipping a plugin
description: Type ids, permissions, testing, limits, lifetimes and protocol versions.
sidebar:
  order: 4
---

## Type ids and namespacing

You write the **short** id — `"shout"`. The service prefixes it with your own package
name, so the published id is `plugin:com.acme.tools/shout`.

Ottomatic derives the same prefix from what the system reports for your service and
refuses anything that does not match, so a collision with another plugin or with a
built-in node is **impossible** rather than merely unlikely.

:::caution
Once a macro has used a node, **its type id is permanent.** A saved workflow stores the
string; renaming it makes every macro using that node break, with no migration. Choose it
as carefully as a database column name.
:::

## Permissions

Declare what you need in **your own** manifest and request it yourself. Ottomatic checks
each permission you list on a node against *your* package and warns in its Problems panel
when one is missing — but it cannot request it or hold it for you.

## Testing your declarations

Copy `sample-plugin`'s test. It runs `PluginDeclarationValidator` — the very object
Ottomatic runs your manifest through before it will show a single node — over your own
declarations, at build time:

```kotlin
val validated = PluginDeclarationValidator.validate(manifest, "com.acme.tools")
assertEquals(emptyList<String>(), validated.rejected.map { it.reason })

// The half a declaration cannot express: whether your node classes can do what they
// declared. PluginDeclarationValidator reads a document, and a document carries no
// interfaces — so this is what catches an @PluginChoice on a node that is not a
// PluginChoiceSource.
assertEquals(emptyList<String>(), PluginNodeContracts.problems(nodes))
```

Without them, a rejected node shows up as a node that is simply **absent from the
palette**, and an unfulfilled contract as a chooser that opens on an empty list — the two
worst shapes a plugin bug can take, because there is nothing on screen to notice.

## What the user sees

A plugin that is merely installed contributes nothing. It appears on Ottomatic's
**Setup → Plugins** screen with the permissions your manifest asks for, and contributes
nodes only once the user turns it on.

That screen also lists **any node Ottomatic refused, with the reason** — the first place
to look when something is missing.

Enabling records your **signing certificate**, not just your package name. An update
signed by the same key is trusted silently; a package with your name signed by somebody
else lands back disabled, with the reason shown.

## Your node's text is never translated by Ottomatic

Ottomatic resolves its own node names, descriptions, port labels and `@Label` config
labels through Android string resources keyed off each node's type id. **Yours cannot go
through that**, and it is worth knowing why rather than filing it as a bug: your
declaration crosses the binder as text you have *already rendered* — `NodeSchema` turns
your `@Label` into a plain `String` inside your own process, long before Ottomatic sees it
— so there is no key for Ottomatic to look up and never will be. Its resource ids would be
meaningless in your APK in any case.

So a plugin node renders exactly the words it declared, in whatever language they were
written, whatever the phone's locale. This is the same fallback path a first-party node
takes when its key is missing, so nothing about it is a special case.

If you want your nodes translated, do it **on your side**: your plugin is an ordinary
Android app, so put your text in your own `res/values-*/strings.xml` and resolve it in
your service before building the declaration. Ottomatic re-reads your declarations when it
binds, so the locale in force at that moment is the one the user sees.

## Lifetimes

Ottomatic binds your service while a macro using your nodes is armed, while the graph
editor is open, and for thirty seconds after the last call. It uses `BIND_AUTO_CREATE`
only — your process gets an ordinary bound-service lifetime and may be killed under memory
pressure.

When it comes back, Ottomatic **re-arms rather than resumes**: your triggers are armed
again from scratch, so you never have to reason about reconnection. `onDestroy` disarms
everything still registered, so a receiver cannot outlive the reason it was registered.

## Limits

| Limit | Value |
| --- | --- |
| Nodes per plugin | 64 |
| Data ports per node | 16 |
| Config fields per node | 24 |
| Execution routes per node | 4 |
| Enum options | 64 |
| Options per `choices` call | 500 |
| Schema nesting | 8 deep |
| Label length | 512 characters |
| Manifest size | 256 KB |
| Per value, each way | 256 KB |
| Log lines per call | 50 |

Exceeding one costs **that node**, with the reason shown on the Plugins screen — never
the whole plugin.

## Protocol versions

`PLUGIN_PROTOCOL_VERSION` is stamped onto your manifest by the SDK, and Ottomatic refuses
a document that does not match the version it speaks. It is the only version comparison in
the system: there is no check of your `versionCode`, because a downgrade is as legitimate
as an upgrade and neither says anything about the wire.

**2** — named execution routes, `@PluginChoice` (both `LIST` and `SCREEN`), `status()`,
and the settings- and chooser-Activity conventions.

**3** — `@IntentChoice`, and the URI grant that goes with it. Bumped rather than accepted
silently, because the manifest is parsed leniently: a version-2 host reading a version-3
declaration would not fail, it would drop the field's *type* and simply not show the row —
so you would see a field you declared quietly missing, with nothing saying why. A refusal
naming both numbers is the better sentence.

Rebuild against the current `:plugin-sdk` and you are on the latest.
