---
title: Writing a plugin
description: A separate Android app that adds nodes to Ottomatic's palette.
sidebar:
  order: 2
---

A plugin is a **separate Android app** that adds trigger, action, value and transform
nodes to Ottomatic's palette. It runs its own code, in its own process, under its own
manifest permissions. **Ottomatic never shares its permissions with it.**

You need two modules on your classpath and nothing else of Ottomatic's:

```kotlin
dependencies {
    implementation("com.example.ottomatic:plugin-sdk:1.0")   // pulls in node-api
}
```

## A node, end to end

```kotlin
@Serializable
data class ShoutConfig(
    @Label("What to shout") @Multiline @Wired val text: String = "",
    @Label("How loudly") val loudness: Loudness = Loudness.LOUD,
)

@Serializable
data class Shouted(val shouted: String, val letters: Int)

class ShoutAction : PluginAction<ShoutConfig, Shouted> {

    override val definition = pluginActionNode<ShoutConfig, Shouted>(
        typeId = "shout",
        displayName = "Shout",
        description = "Puts text into capitals",
        icon = NodeIcon.SEND,
        output = dataOut<Shouted>("shouted", label = "Shouted"),
    )

    override suspend fun execute(config: ShoutConfig, context: PluginContext): PluginOutput<Shouted> {
        val shouted = config.text.uppercase()
        context.log("Shouted " + shouted.length + " characters")
        return PluginOutput(Shouted(shouted, shouted.length))
    }
}
```

Then one service and one manifest entry:

```kotlin
class MyPluginService : BaseOttomaticPluginService() {
    override val nodes = listOf(ShoutAction(), /* … */)
}
```

```xml
<service android:name=".MyPluginService" android:exported="true"
         android:permission="com.example.ottomatic.permission.BIND_PLUGIN">
    <intent-filter>
        <action android:name="com.example.ottomatic.action.PLUGIN" />
    </intent-filter>
</service>
```

That is the whole integration. There is no AIDL to write, no JSON, no threading and no
dispatch table — the SDK marshals every argument and result generically, once, for every
plugin that will ever exist.

A worked example lives in `sample-plugin/` in the repository, with an action, a value, a
transform and a trigger.

## The four node kinds

| Kind | Contract | Notes |
| --- | --- | --- |
| Action | `PluginAction<C, O>` / `PluginEffect<C>` | `execute` returns a `PluginOutput`; `route` must be one it declared |
| Trigger | `PluginTrigger<C, O>` / `PluginPulseTrigger<C>` | `arm` registers and returns the handle that releases it. Not suspending, callback-based — no coroutines needed |
| Value | `PluginValue<C, O>` | A pure leaf read. **Answer null rather than throwing.** Bounded at 2 s |
| Transform | `PluginTransform<C, O>` | A pure function of its `@Wired` inputs. Needs at least one |

**A value must be cheap and must not fail.** It is read outside the execution order, at a
moment its consumer decides, so a read that overruns its two-second bound answers null,
the consumer falls back to its form value, and a comparison fails closed. Anything
expensive or failable belongs in an action, which gets thirty seconds.

Not available, each for its own reason: **loops** (a thousand iteration maps in one binder
call would blow the transaction limit — wire a *Repeat* around your node instead),
**adaptive ports** (the host resolves those by walking the graph, which a plugin cannot be
given), and **forks**.

## Config classes

The rules are Ottomatic's own, unchanged:

- one `@Serializable` data class per node;
- **every property has a default**, so the node can run unconfigured;
- every property is a `String`, a number, a `Boolean`, an `enum` or a `DateTime`.

`@Label` names the form row. `@Multiline` makes it a text area. `@Wired` gives the
property a **socket** on the card so an upstream node can feed it — the port's name *is*
the config key, so the two can never disagree. `@VisibleWhen` hides a row until a sibling
holds a given value. `@TimeOfDay` renders a clock face.

Most of the host's other widgets are deliberately **not** available: `@Picker`, `@Ports`,
`@PhoneNumber`, `@WifiNetwork`, `@ContactName`, `@FilePath`, `@Tools`, `@ApiToken` and
`@Suggested`. Each reaches a host library or the user's own records — `@Picker` alone
spans geofence places, variables, macros, mail accounts, smart-home hubs and AI
connections — so a plugin asking for one would be handed the user's data by a field it
merely asked to render. Using one fails loudly the first time your service starts.

Two run the other way, and are the subject of their own page:
[`@PluginChoice` and `@IntentChoice`](/docs/extend/plugin-choosers/).

## Saying that it failed

Your action is a call to somebody else's server, so *it was rejected* is the second
ordinary outcome, not an exception. Name the outcomes and they become ports on the card:

```kotlin
override val definition = pluginActionNode<PostConfig, Posted>(
    typeId = "post",
    displayName = "Post to board",
    description = "Posts text to a board",
    icon = NodeIcon.SEND,
    output = dataOut<Posted>("posted", label = "Posted"),
    execOutputs = routes(
        "out" to "When posted",
        "error" to "When it fails",
    ),
)

override suspend fun execute(config: PostConfig, context: PluginContext): PluginOutput<Posted> {
    val result = api.post(config.board, config.text)
        ?: return PluginOutput.failed("error")
    return PluginOutput(Posted(result.id, config.board))
}
```

`ExecOutputsWire.Single` (one `out`) and `ExecOutputsWire.Branch` (`true` / `false`, for a
node that genuinely answers a *question*) are still there and still the defaults. Reach
for `routes(...)` when the two branches are outcomes rather than a yes and a no.

Three rules:

- **Declare the "carried on" route first.** Ottomatic lands two things on the first route
  you name: a `route` string your declaration does not contain, and a call it could not
  make at all. That second one is why `error` must not be first — an unreachable plugin
  may well have done the work and failed on the way back, so the host must not claim it
  did not.
- **A failure carries no data.** `PluginOutput.failed(...)` emits nothing on your data
  port, which is what `PluginOutput.value` being nullable is for. A `Posted("", "")` in
  its place reads downstream exactly like a success.
- **Prefer a route to `halt`.** Routing lets the macro *handle* the failure; halting only
  ends it.

## Saying you are not ready

A plugin holding every permission it asked for and doing nothing because nobody has
signed in is the failure Ottomatic cannot see from outside: the graph is perfect and the
macro looks armed. So say so:

```kotlin
class MyPluginService : BaseOttomaticPluginService() {
    override val nodes = listOf(PostAction())

    override fun status(): PluginStatusWire = when {
        Account.isSignedIn(applicationContext) -> PluginStatusWire()
        else -> PluginStatusWire(ready = false, message = "Nobody is signed in. Open Acme Tools to sign in.")
    }
}
```

Your sentence lands as a warning on **every placed node of yours**, in the Problems panel
and on the workflow list's count. It blocks nothing — the graph is finished, and signing
in starts it working with no edit to the macro.

Asked on every refresh under a **two-second** bound, so keep it local: read a token out of
your own preferences, do not validate it over the network. A plugin that does not answer
in time leaves Ottomatic saying nothing at all, which is deliberate — a panel that badges
every node on a guess is worse than one that waits until it knows.

## A settings screen

Ottomatic withholds its credential libraries from plugins, so your sign-in lives in your
own app. Export an Activity under `com.example.ottomatic.action.PLUGIN_SETTINGS` and the
Plugins screen offers a **Set up** button to it:

```xml
<activity android:name=".SettingsActivity" android:exported="true" android:label="Acme Tools">
    <intent-filter>
        <action android:name="com.example.ottomatic.action.PLUGIN_SETTINGS" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Ottomatic resolves this against **your package** and launches the result by component —
never from anything you send over the binder, which is why this is a manifest convention
rather than a field. The button is offered whether or not the user has enabled you, since
signing in first is the natural order. Returning from it re-reads your `status()`, so the
warning above clears on its own.

The Activity needs nothing of Ottomatic's: no SDK class, no theme, no library.

## Next

- [Config choosers](/docs/extend/plugin-choosers/) — offering lists of your own, and
  asking other apps
- [Shipping a plugin](/docs/extend/plugin-shipping/) — type ids, permissions, testing,
  limits, lifetimes and protocol versions
