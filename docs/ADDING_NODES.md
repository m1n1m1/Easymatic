# Adding a node

A node is declared **exactly once**, in its own file under `engine/`, and registered by
adding it to one list. Everything else — the palette, search, the config form, the
Problems panel, the Permissions screen, drag-to-create suggestions, `action.if`'s source
dropdown — derives from that declaration with no second registration anywhere.

This is the first-party counterpart to `PLUGINS.md`. A plugin is a separate app that
reaches the same palette across a binder; a node is compiled in, and gets the config
widgets and the loop, fork and adaptive-port shapes a plugin is denied.

## The 30-second version

The minimum really is **two files**: the node, and the registry line.

| What | Where |
|---|---|
| Write the node | `app/src/main/java/com/example/ottomatic/engine/{action,trigger,value,transform}/<Name>.kt` |
| Register it | `app/src/main/java/com/example/ottomatic/domain/registry/{Action,Trigger,Value,Transform}Registry.kt` |
| *If it emits a struct* | `app/src/main/java/com/example/ottomatic/domain/model/items/Items.kt` |
| *If it needs a new icon* | `node-api/…/domain/model/NodeIcon.kt` **and** `app/…/feature/grapheditor/EditorColors.kt` |
| *If it needs a grant* | `permissions =` in the definition, `app/src/main/AndroidManifest.xml`, `app/…/feature/permissions/PermissionCopy.kt` |
| *If its ports depend on the graph* | `app/src/main/java/com/example/ottomatic/domain/registry/EffectivePorts.kt` |

`NodeTypeRegistry` and `ConfigSchemaRegistry` are **derived views** of the four
registries. Never add an entry to either one — a node that appears there but not in its
kind registry has metadata and no behaviour behind it.

## Choosing a kind

There are four `NodeKind`s, and the choice is forced by what the node *is* rather than
by taste.

An **action** does something: it has an execution input, at least one execution output,
and may produce one typed data item. A **trigger** is an event source: exactly one
execution port, an output, and no data inputs at all. A **value** is a pure leaf read —
no execution ports, no data inputs, exactly one data output. A **transform** is a pure
function — no execution ports, at least one data input, exactly one data output.

Values and transforms are the *pull* side. They are never pulsed; they are read just
before the node that consumes them, memoized per consuming node. That is sound only
while the read is cheap and repeatable, so **a value answers `null` rather than
throwing** — the consumer then falls back to its form value and a comparison fails
closed. Anything expensive or failable is an action.

**Every trigger over a readable state gets a value node too**, added in the same change,
sharing its reading and classification code rather than re-deriving it. A trigger
answers "tell me when this changes"; a value answers "what is it right now?", and a
state with only the trigger half forces the user to arm a second macro just to remember
what the first one saw. Skip the value only when the trigger is an **event** with no
resting value — a shake, a tap, an SMS, a boot — or when reading it is expensive or
failable, which is an action's job. The rule does not run backwards: a value with no
trigger behind it is fine, and `value.nfc` is one.

"Expensive or failable" is about the **transport, not the subject**. `value.ha_state`
reads a Home Assistant entity — unambiguously a thing on the network — and is a legal
value node, because a websocket pushes every change into a local cache and the read is a
map lookup. `value.light_state` does not exist because a Hue bridge offers no such
channel and every read is a round trip. So ask *is this read cheap, and can it fail*,
not *does this concern the network*.

There is deliberately **no condition kind**. A condition is a comparison over a value,
so the two halves are declared separately and combined at `action.if`.

## A node, end to end

`value.now` is the whole of a node, in 36 lines:

```kotlin
package com.example.ottomatic.engine.value

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.valueNode

/**
 * `value.now` — the current date and time.
 *
 * The clock reader every other date node is measured against. Being a value node it
 * is *pulled* rather than pulsed, and it can be named directly as an `action.if`
 * source with no edge and no execution position.
 */
class NowValue : ValueNode<NoConfig, DateTime> {

    override val definition = valueNode<NoConfig, DateTime>(
        typeId = "value.now",
        displayName = "Current time",
        description = "The current date and time",
        category = NodeCategory.VALUE_TIME,
        icon = NodeIcon.SCHEDULE,
        output = dataOut("now", label = "Now"),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): DateTime = DateTime.now()
}
```

…plus `NowValue(),` in `ValueRegistry`. That is the entire integration.

An action with configuration is the same shape with a data class in front of it. Note
that the one `@Wired` property gives the node **both** the form row and the `message`
DATA input port — the port's name *is* the config key, so the two can never disagree:

```kotlin
@Serializable
data class LogConfig(
    @Label("Message") @Multiline @Wired val message: String = "",
)

class LogAction : Action<LogConfig, Unit> {

    override val definition = effectNode<LogConfig>(
        typeId = "action.log",
        displayName = "Log Message",
        description = "Writes a message to this workflow's console",
        category = NodeCategory.FLOW_CONTROL,
        icon = NodeIcon.BOLT,
    )

    override suspend fun execute(input: LogConfig, context: ExecutionContext): NodeOutput<Unit> {
        context.log(input.message)
        return NodeOutput(Unit)
    }
}
```

## The builders

All eleven live in `engine/NodeDefinition.kt`, one per node *shape*. Each takes
`typeId`, `displayName`, `description`, `category` and `icon`; the rows below name what
else distinguishes them.

| Builder | Reach for it when |
|---|---|
| `actionNode<I, O>` | An action producing a typed data item on `output` |
| `effectNode<I>` | An action with no data output. Also carries `extraPorts` and `hasDynamicPorts` |
| `adaptiveNode<I>` | Ports resolved from the graph. `extraPorts` required, `hasDynamicPorts` forced |
| `loopNode<I>` | Forces `ExecOutputs.LOOP`; ports stay static |
| `triggerNode<C, O>` | A trigger emitting an event struct, plus optional `extraOutputs` projections |
| `pulseTriggerNode<C>` | A trigger carrying no data |
| `adaptiveTriggerNode<C>` | A trigger whose data outputs are named in its own config. There is exactly one: `trigger.api` |
| `valueNode<C, O>` | A typed leaf read |
| `adaptiveValueNode<C>` | A leaf whose type comes from config. There is exactly one: `value.variable` |
| `transformNode<C, O>` | A pure function over `@Wired` config properties |
| `rawTransformNode<C>` | Reads a *declared* port, so only a raw `Item` can come off it — but its output type is fixed |
| `adaptiveTransformNode<C>` | Output type resolved from config; `output` must be `ItemSchema.Wildcard` |

Three things surprise people. **`loopNode` is separate from `adaptiveNode`** because
`action.repeat`'s ports are static — being a loop does not make a node adaptive.
**There is no `forkNode`**: a fork is `effectNode(execOutputs = ExecOutputs.FORK)` plus
the `ForkAction` contract. And `effectNode` carries `extraPorts`/`hasDynamicPorts`
deliberately, for the nodes that need one without being a `RawAction` or a `LoopAction`
— `action.list_add` takes a wildcard port without being adaptive, and
`action.set_variable` keeps ordinary typed config while wanting its `value` port
retyped.

`execOutputs` picks the execution ports: `SINGLE` (`out`), `BRANCH` (`true`/`false`),
`LOOP` (`body`/`completed`), `ACKNOWLEDGED` (`out`/`timed_out`), `DECISION`
(`confirmed`/`cancelled`/`timed_out`) and `FORK` (`out`/`resumed`). Both loop and fork
outputs are *forward* edges — nothing is ever wired back — so the graph stays acyclic
and neither the validator's cycle rule nor the executor's path guard needs an exception.

## The contract to implement

The interfaces are in `engine/NodeContracts.kt`, except the trigger's, which is in
`engine/trigger/Trigger.kt`.

| Interface | Method |
|---|---|
| `Action<I, O>` | `suspend fun execute(input: I, context): NodeOutput<O>` |
| `RawAction<C>` | `suspend fun executeRaw(config: C, input: NodeInput, context): NodeOutput<Map<PortName, Item>>` |
| `LoopAction<C>` | `suspend fun iterations(config: C, input: NodeInput, context): List<Map<PortName, Item>>` |
| `ConditionalLoopAction<C>` | `suspend fun nextPass(config: C, input: NodeInput, context, pass: Int): Map<PortName, Item>?` |
| `ForkAction<C>` | `suspend fun begin(config: C, input: NodeInput, context): Fork` |
| `Trigger<C, O>` | `fun activate(config: C, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<O>>` |
| `ValueNode<C, O>` | `suspend fun read(config: C, context): O?` |
| `RawValue<C>` | `suspend fun readItem(config: C, context): Item?` |
| `TransformNode<C, O>` | `suspend fun transform(config: C, context): O?` |
| `RawTransform<C>` | `suspend fun transformItem(config: C, input: NodeInput, context): Item?` |

**A trigger's `activate` is not suspending** — it returns a `Flow` that the runner
collects, so registration happens on the caller's thread and teardown belongs in the
flow's own cancellation. It is also the one contract handed its own `WorkflowNode`,
which it needs to key a platform registration by node id; an action, value or transform
gets its decoded config and never sees the node it was decoded from. Only the raw and
adaptive contracts additionally receive a `NodeInput` to read declared ports off.

Most values extend `DeviceValue` rather than implementing `ValueNode` directly, which
reduces a reader to a `readValue(context): O?` of about eleven lines. `NowValue` is the
exception because it reads a clock rather than a device subsystem, so there is nothing
that can fail and nothing to report as unreadable.

Two caps are announced in the run log rather than thrown: `MAX_ITERATIONS` of 1 000 on
the loops, and `MAX_PENDING_WAITS` of 64 on forked branches process-wide.

## Config classes

One `@Serializable` data class per node. **Every property must have a default**, or the
registry fails to initialise with a message naming the class — the node has to be able
to run unconfigured. Every property must be a `String`, a number, a `Boolean`, an `enum`
or a `DateTime`; a `List` is rejected outright. Use `NoConfig` when there is nothing to
configure.

The annotations, all in `domain/model/config/ConfigAnnotations.kt`: `@Label` names the
row, `@Multiline` makes it a text area, `@Wired` gives it a socket, and `@VisibleWhen`
hides it until a sibling holds a given value. Then the ten widget annotations —
`@Picker`, `@Ports`, `@PhoneNumber`, `@TimeOfDay`, `@WifiNetwork`, `@ContactName`,
`@FilePath`, `@IntentChoice`, `@Suggested` and `@ApiToken` — of which **at most one may
claim a property**, and each requires a `String`. A second is a registry-initialisation
failure, not something the form renders around.

`@Picker` is for an identifier chosen from a chooser, never typed: a mistyped identifier
does not fail loudly, it names something else or nothing, and the node just looks broken.
The six editable-with-a-chooser widgets exist because a phone number, a time of day, a
network name, a person's name, a file path and whatever another app hands back are things
people genuinely type, and because the answer set is wider than any chooser can offer — the
office Wi-Fi cannot be scanned from home, and the file a macro is about to write does not
exist yet.

`@IntentChoice` is the one whose chooser this app does not draw: it names an implicit
`Intent` and whatever app the phone resolves it to fills the field in. **Reach first for the
requests Android itself answers** — `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`,
`ACTION_OPEN_DOCUMENT_TREE`, `ACTION_RINGTONE_PICKER` — which work on every phone and cost
no permission at either end; a barcode scanner or a camera is expressible but is answered
only where the app or the grant happens to be there. Prefer `ACTION_OPEN_DOCUMENT` to
`ACTION_GET_CONTENT`, because only the first conveys a grant that can be persisted, and the
second gives a value that works until the editor closes and then fails silently forever.
Note that `inputExtras` carries **strings only** and does so quietly, so a request needing a
typed extra to be correct is one it cannot express. It is also the **only** widget a plugin
may declare besides `@PluginChoice`, since it reaches nothing of the user's that Ottomatic
keeps. Adding a
widget annotation costs one entry in `NodeSchema.widgetFlags` as well as its branch in
`stringFormType` — miss that one and two widgets on a property are silently allowed.
Adding a `PickerKind` or a `ConfigFieldType` requires a matching branch in
`ConfigFieldEditor`'s exhaustive `when`; reusing one costs nothing.

Two rules cause real bugs. A `@Ports` property's **default must be what "nothing
configured" parses to**, because decode substitutes the default for a blank value while
`effectivePorts` reads the raw config and sees blank — any other default makes the ports
on the card disagree with the ones the node binds. And a DATA input derived from a
`@Wired` property is **hidden until opted in** with the socket toggle, whereas a
declared DATA input with no config field behind it is **always shown**, because there is
no form row to opt in from.

A nullable enum gets a blank option labelled "Any" prepended automatically, which is how
a filter says "no filter". Prefer an existing shared config to a new one — `ToggleConfig`
serves the plain on/off device actions and `EventFilter<E>` the broadcast triggers.

## Registering

Add the node to the list in its kind's registry. In practice that is an **import line
and a constructor call**, not literally one line:

```kotlin
    private val values: List<ValueNode<*, *>> = listOf(
        AirplaneModeValue(),
        BatteryLevelValue(),
        …
        NfcValue(),
        NowValue(),
        …
    )
```

**Order is user-visible** — the palette renders in registry order, so where you put the
line is a design decision rather than a formality. `ValueRegistry` is alphabetical.
`TransformRegistry` puts the four general transforms first and then keeps the seven list
ones together. `ActionRegistry` deliberately keeps the three loops together and out of
alphabetical order, as it does the dialog, mail and messenger families. `TriggerRegistry`
is a `buildList` grouped by activation tier. Follow the grouping you land in rather than
sorting.

Registering lights up every use at once, with no second registration anywhere: the
palette and its search, drag-to-create suggestions (`NodeSuggestions`), the config form
(`ConfigSchemaRegistry`), the Problems panel's permission warnings
(`GrantedPrerequisites`), the Permissions screen (`PermissionCatalogue`) and — for a
value — `action.if`'s source dropdown, where it can be read with no edge drawn to it.

## Rules your declaration must satisfy

`NodeDeclarationRules` in `:node-api` is the single authority, with three callers: the
plugin loader, a plugin author's own test, and `NodeDeclarationRulesTest` over the app's
own nodes. Every message completes the sentence "Node `<typeId>` …".

**Ports.** No two ports of the same direction share a name. A DATA port **must** carry a
schema — one without type-checks against nothing, so every edge into it is accepted and
nothing downstream ever narrows. An EXECUTION port **must not** carry one, since a
declaration whose author expected data to flow down a pulse is a misunderstanding worth
catching. No port may have a blank label.

**Config.** Keys are unique and non-blank, labels are non-blank, an enum field has at
least one option, and its default **is one of them** — unchecked, that shows as a form
opening on a blank selection matching no branch the node tests for. A `@VisibleWhen`
rule must name a real sibling, may not point at its own field, must use values the
controller can actually hold, and the visibility chain must not cycle.

**By kind.** A trigger has exactly one execution port and it is an output, and no data
inputs. An action has an execution input and at least one execution output. A value has
no execution ports, no data inputs, and exactly one data output. A transform has no
execution ports, at least one data input, exactly one data output, and **may not require
a permission**.

A **value may** declare a permission, which changed in 2026-08 when `value.wifi_network`
arrived: forbidding it never made a read safe, it only made it silent, since the
Problems panel and the node's own card both walk declarations. The contract that remains
is about ports and effects. Declaring one is still a judgement rather than a reflex: a
value whose whole job is to answer "is the radio on?" must *not* declare the radio,
because badging it in the Problems panel for the radio being off would be badging it for
working. That prerequisite belongs on the trigger, which genuinely cannot fire without
it.

The rule that reads as arbitrary is **exactly one data output** on a value and a
transform. It is load-bearing: the executor's pull memo is keyed by node, not by port.

`NodeTypeRegistryTest` adds one more that is easy to trip over — a typeId must start
with `trigger.`, `action.`, `value.` or `transform.` to match its kind.

## Icons, categories, permissions and structs

**An icon** is a `NodeIcon` enum member in `:node-api`. Reusing one costs nothing;
adding one is a **two-file** change, because `nodeIcon()` in
`feature/grapheditor/EditorColors.kt` is an exhaustive `when` and will not compile until
the icon is drawn. That is the point — the string keys it replaced fell back silently to
a placeholder when misspelled.

**A category** is a `NodeCategory` member carrying its own `kind` and `displayName`, so
it is self-contained and needs no UI mapping. Note that a category with no node in it
fails the build, and that `definition.kind` must equal `definition.category.kind`.

**A permission** is declared inline, and declaring it is what makes it visible
everywhere:

```kotlin
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.RECEIVE_SMS.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "sms.receive",
            ),
        ),
```

The `<uses-permission>` entry must also exist in `AndroidManifest.xml`. The Permissions
screen row and the Problems-panel warning both derive themselves, but **the copy does
not**: a `rationaleKey` absent from `PermissionCopy.rationaleFor` renders no card at all
on the node's config panel, which fails quietly rather than at compile time. A
requirement that depends on *config* rather than on type is not declared statically —
`action.call`'s contacts access is derived by `usesContacts` instead.

**A capability** is the second axis beside a permission, declared the same way:

```kotlin
        capabilities = listOf(DeviceCapability.FINGERPRINT_GESTURES),
```

The rule for choosing is one sentence: **declare a permission for something the user
can go and grant, and a capability for hardware the phone may simply not have.** They
are separate because they reach different screens — a permission belongs on the
Permissions screen because that is where it gets fixed, and a capability must stay off
it, since a row that can never go green is not an answer to "what do I need to do?".
`AndroidPermissionChecker` already made that call from the other side, reporting a phone
with no NFC chip as *satisfied*. Both produce a Problems-panel warning that blocks
nothing.

`CapabilityChecker` answers a tri-state, and `UNKNOWN` is not a lapse: some hardware
questions can only be asked of a bound service, and a phone nobody has been able to ask
must read as silence rather than as a warning. Only `UNAVAILABLE` warns.

**A struct** emitted by a trigger or action is a plain `@Serializable data class` in
`domain/model/items/Items.kt`. Timestamps are `DateTime`, never `Long`. The house shape
for a "did it work?" receipt is a `changed: Boolean` and an `error: String = ""`. Prefer
an existing struct — the broadcast triggers all reuse `SystemState` rather than adding
one each.

## Adaptive ports

Only if `hasDynamicPorts = true`. Add a branch to the `when` over typeId in
`EffectivePorts.kt`, and declare the typeId, port names and config keys as constants at
the top of that file, where every such constant in the app lives.

Two things cost real time. A branch returning a fresh list **replaces the declared port
set wholesale**, so execution ports must be re-declared by hand. And the
`visiting: Set<NodeId>` recursion guard must be threaded through, because resolution
walks the graph both backwards and forwards and re-entering a node has to fall back to
its declared ports.

The guard is only needed by a node that *asks the graph* something. `action.script` and
`trigger.api` read their ports out of their own config, so their resolvers take neither
`workflow` nor `visiting` — which is most of what makes those two cheap.

**Triggers can be adaptive too**, via `adaptiveTriggerNode`, and the shape differs from
the other three in a way worth knowing: an adaptive transform or value declares a
wildcard placeholder port and has it **retyped**, where an adaptive trigger declares no
data port at all and has ports **added**. Their output count is known and only its type
is not; a trigger carrying nothing is as ordinary as one carrying three values. That is
also why declaring `output = null` keeps `NodeDeclarationContractTest`'s "a trigger's
data outputs are its event plus its projections" rule *true* rather than weakening it.

`NodeDeclarationContractTest` fails until the branch exists, so a node marked adaptive
and then forgotten cannot reach the palette — one test per kind, including
`every adaptive trigger grows its declared ports from config`.

An adaptive node whose ports come from config must also be added to
`GraphEditorViewModel.retypesDataPorts`, or editing the port list leaves stranded edges
behind. That is not covered by any contract test.

## Testing

Three global tests already cover a new node the moment it is registered:
`NodeDeclarationRulesTest` runs the shared rules over every node,
`NodeDeclarationContractTest` adds what needs a graph or the registries — that config
decodes unconfigured, that each `@Wired` property becomes exactly one data input, that
an action's declared output is the one it encodes onto, that a trigger's data outputs
are exactly its event plus its projections — and `NodeTypeRegistryTest` pins uniqueness,
the kind prefix and the category invariants.

Beyond those, the convention is a `<Feature>RegistryTest.kt` in
`app/src/test/java/com/example/ottomatic/domain/registry/` asserting registry
membership, kind and category, and port shape; plus a behaviour test in
`app/src/test/java/com/example/ottomatic/engine/<kind>/`.

There is **no fake `ExecutionContext`**. Tests use the real `DefaultExecutionContext` and
fake what it depends on — `RecordingSystemServices` is the shared recording
`SystemServices`, and log capture is a lambda:

```kotlin
/**
 * `action.open_url` over the shapes a URL field actually holds.
 *
 * The load-bearing assertions are that the platform receives the **normalized**
 * URL rather than what was typed, and that text which is not a URL reaches it
 * **not at all**.
 */
class OpenUrlActionTest {

    private val services = RecordingSystemServices()
    private val context = DefaultExecutionContext(systemServices = services)
    private val action = OpenUrlAction()

    @Test
    fun `a bare host is opened over https`() = runBlocking {
        action.execute(OpenUrlConfig(url = "google.com"), context)
        assertEquals(listOf("https://google.com"), services.openedUrls)
    }
```

Note the class-level KDoc naming the load-bearing assertion — that convention is on
essentially every test file here, and a per-test comment appears wherever a case encodes
a past bug.

Verify with `.\gradlew.bat assembleDebug test detekt`. Skip `lintDebug`; it has
pre-existing errors unrelated to the node system.

## Pitfalls

**A typeId is permanent** once a macro has used it. A saved workflow stores the string,
and the schema version gates load — an older workflow is discarded rather than migrated,
so renaming a typeId breaks every macro using that node with no way back. Choose it as
carefully as a database column name. This is why the smart-home nodes say `light` rather
than `hue` and the messenger nodes say `message` rather than `whatsapp`: the vendor
lives in the description, not the id.

Labels and descriptions are still written as **plain Kotlin strings right here**, and that
does not change: `@Label` is an annotation, so its argument can only be a compile-time
constant, and half of this text lives in `:node-api`, which is compiled without
`android.jar` and cannot reach `getString`. What the string you write here now *also* does
is act as the English source a translation key is generated from, and as the fallback when
none resolves — so write it exactly as before and run the regeneration command in the
**Node text and translation** section of `CLAUDE.md` afterwards. `NodeStringsSyncTest`
fails and names the key if you forget. The
**description is searched** by the palette, so it is where a node's alternate names
belong ("Philips Hue" is findable only because it is in the description). Write
descriptions as sentence fragments with no trailing period, leading with a verb for an
action and "The …" or "Whether …" for a value.

Never edit `NodeTypeRegistry` or `ConfigSchemaRegistry`. A value that throws instead of
answering `null` breaks the pull side's fail-closed contract. And a value node that
needs config is fine — `value.variable` has it — but purity is still about ports and
effects, so it keeps no execution ports and no data inputs.
