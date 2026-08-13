# Writing an Ottomatic plugin

A plugin is a **separate Android app** that adds trigger, action, value and transform
nodes to Ottomatic's palette. It runs its own code, in its own process, under its own
manifest permissions. Ottomatic never shares its permissions with it.

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
        context.log("Shouted ${shouted.length} characters")
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
dispatch table — the SDK marshals every argument and result generically, once, for
every plugin that will ever exist.

A worked example lives in `sample-plugin/`, with an action, a value, a transform and a
trigger.

## Config classes

The rules are Ottomatic's own, unchanged:

- one `@Serializable` data class per node;
- **every property has a default**, so the node can run unconfigured;
- every property is a `String`, a number, a `Boolean`, an `enum` or a `DateTime`.

`@Label` names the form row. `@Multiline` makes it a text area. `@Wired` gives the
property a **socket** on the card so an upstream node can feed it — the port's name
*is* the config key, so the two can never disagree. `@VisibleWhen` hides a row until a
sibling holds a given value. `@TimeOfDay` renders a clock face.

Six of the host's config widgets are deliberately **not** available: `@Picker`,
`@Ports`, `@PhoneNumber`, `@WifiNetwork`, `@ContactName` and `@MailFolder`. Each
reaches a host library or the user's own records — `@Picker` alone spans geofence
places, variables, macros, mail accounts, smart-home hubs and AI connections — so a
plugin asking for one would be handed the user's data by a field it merely asked to
render. Using one fails loudly the first time your service starts.

## The four node kinds

| Kind | Contract | Notes |
|---|---|---|
| Action | `PluginAction<C, O>` / `PluginEffect<C>` | `execute` returns a `PluginOutput`; `route` must be `out`, or `true`/`false` for a `BRANCH` node |
| Trigger | `PluginTrigger<C, O>` / `PluginPulseTrigger<C>` | `arm` registers and returns the handle that releases it. Not suspending, callback-based — no coroutines needed |
| Value | `PluginValue<C, O>` | A pure leaf read. **Answer null rather than throwing.** Bounded at 2 s |
| Transform | `PluginTransform<C, O>` | A pure function of its `@Wired` inputs. Needs at least one |

**A value must be cheap and must not fail.** It is read outside the execution order, at
a moment its consumer decides, so a read that overruns its two-second bound answers
null, the consumer falls back to its form value, and a comparison fails closed.
Anything expensive or failable belongs in an action, which gets thirty seconds.

Not available, each for its own reason: loops (a thousand iteration maps in one binder
call is a `TransactionTooLargeException` — wire an `action.repeat` around your node
instead); adaptive ports (the host resolves those by walking the graph, which a plugin
cannot be given); and forks.

## typeIds and namespacing

You write the **short** id — `"shout"`. The service prefixes it with your own package
name, so the published id is `plugin:com.acme.tools/shout`. Ottomatic derives the same
prefix from what `PackageManager` reports for your service and refuses anything that
does not match, so a collision with another plugin or with a built-in node is
impossible rather than merely unlikely.

Once a macro has used a node, **its typeId is permanent.** A saved workflow stores the
string; renaming it makes every macro using that node break with no migration. Choose
it as carefully as a database column name.

## Permissions

Declare what you need in **your own** manifest and request it yourself. Ottomatic
checks each permission you list on a node against *your* package and warns in its
Problems panel when one is missing — but it cannot request it or hold it for you.

## Testing your declarations

Copy `sample-plugin`'s test. It runs `PluginDeclarationValidator` — the very object
Ottomatic runs your manifest through before it will show a single node — over your own
declarations, at build time:

```kotlin
val validated = PluginDeclarationValidator.validate(manifest, "com.acme.tools")
assertEquals(emptyList<String>(), validated.rejected.map { it.reason })
```

Without it, a rejected node shows up as a node that is simply *absent from the palette*,
which is the worst shape a plugin bug can take.

## What the user sees

A plugin that is merely installed contributes nothing. It appears on Ottomatic's
**Plugins** screen (in the ⋮ menu on the workflow list) with the permissions your
manifest asks for, and contributes nodes only once the user turns it on. That screen
also lists any node Ottomatic refused, with the reason — the first place to look when
something is missing.

Enabling records your **signing certificate**, not just your package name. An update
signed by the same key is trusted silently; a package with your name signed by somebody
else lands back disabled with the reason shown.

### Your node's text is never translated by Ottomatic

Ottomatic resolves its own node names, descriptions, port labels and `@Label` config
labels through Android string resources, keyed off each node's typeId. **Yours cannot
go through that**, and it is worth knowing why rather than filing it as a bug: your
declaration crosses the binder as text you have *already rendered* — `NodeSchema` turns
your `@Label` into a plain `String` inside your own process, long before Ottomatic sees
it — so there is no key for Ottomatic to look up and never will be. Its resource ids
would be meaningless in your APK in any case.

So a plugin node renders exactly the words it declared, in whatever language they were
written, whatever the phone's locale. This is the same fallback path a first-party node
takes when its key is missing, so nothing about it is a special case.

If you want your nodes translated, do it **on your side**: your plugin is an ordinary
Android app, so put your text in your own `res/values-*/strings.xml` and resolve it in
your service before building the declaration. Ottomatic re-reads your declarations when
it binds, so the locale in force at that moment is the one the user sees.

## Lifetimes

Ottomatic binds your service while a macro using your nodes is armed, while the graph
editor is open, and for thirty seconds after the last call. It uses `BIND_AUTO_CREATE`
only — your process gets an ordinary bound-service lifetime and may be killed under
memory pressure.

When it comes back, Ottomatic **re-arms rather than resumes**: your triggers are armed
again from scratch, so you never have to reason about reconnection. `onDestroy` disarms
everything still registered, so a receiver cannot outlive the reason it was registered.

## Limits

64 nodes per plugin, 16 data ports and 24 config fields per node, 64 enum options,
schema nesting 8 deep, 512-character labels, a 256 KB manifest, 256 KB per value each
way, and 50 log lines per call. Exceeding one costs **that node**, with the reason shown
on the Plugins screen — never the whole plugin.
