---
name: notifications
description: Read before touching the notifications Easymatic *posts* — `action.notify`, `action.notify_cancel`, the `Notifications` facade, `AndroidNotifications`, `NotificationResponses`, `NotificationResponseReceiver` and `ForegroundGrant`. Covers why posting is a fork rather than a question, why the answer is data instead of one exec port per button, why being ignored pulses nothing, and why there is still only one channel.
---

# Posting notifications

`action.notify` posts a notification and — when it offers a button or a reply field —
carries on again once somebody reacts. `action.notify_cancel` takes one
back down. Both live in `engine/action/`, the facade is `core/service/Notifications.kt`,
and the Android half is `data/notification/`.

**This is the posting side and nothing else.** Reading and pressing *other* apps'
notifications is `Messaging` (`AndroidMessaging`, `ActiveNotifications`,
`NotificationListener`) and needs notification access; nothing here does. The two never
meet: one addresses a tag this app chose, the other a `ConversationRef` somebody else's
app minted, and neither handle means anything to the other facade. See the `messengers`
skill for that half.

## A fork, not a question

The dialog nodes **block** — `Prompts.ask` suspends the walk until the window is
answered — which is right for a modal window over whatever the user is looking at. A
notification is the opposite: it waits in a shade, possibly for hours, and the rest of
the macro has no reason to stand still for it. So `action.notify` is a `ForkAction`:
`out` pulses at once, `resumed` pulses if and when somebody reacts. `action.wait_until`'s
shape with a person in place of a clock.

That also settles the limit the `dialog-nodes` skill wrote down: an overlay dialog does
not appear over the lock screen, so with the display off it waits behind it and times
out. A notification does appear there, which is what makes "reach somebody who is not
holding their phone" expressible at all.

`ExecOutputs.ANSWERABLE` is `FORK`'s ports under a second name for the deferred one:
`ExecPorts.ANSWERED_LABEL` ("When answered") rather than `RESUMED_LABEL` ("When the time
comes"), because the second promises a moment that will arrive and nobody may ever touch
a notification. The **port name stays `resumed`**, which is the whole trick —
`GraphValidator.isFork()` finds the node by the port it already looks for, and
`WorkflowExecutor` pulses it through the route it already has, so neither needed a line
changing.

## The answer is data, not one branch per button

`action.dialog_choice` settled this and the argument carries over unchanged: an exec
output per option would make the node's *shape* depend on the contents of a text field,
and would be a third way of branching beside `action.if`. So the answer leaves on
`button` and `index` — the words `action.for_each` already uses — plus `reply`, and the
graph branches on it with the comparison it has.

A tap on the body is `index = NotificationAnswer.TAPPED` (-1) with an empty label, so
"if it was a button do this, otherwise open the map" is one `action.if`. **What a tap
opens is likewise nothing this node knows**: wire `action.open_url` or `action.launch_app`
to `resumed`. A dropdown in the node could only ever name one destination, where the
whole point is that it is more than one.

**A tap is never enough on its own.** Every notification is tappable, so arming the
branch on a tap would give every plain `action.notify` a second branch it never asked
for — and a switch to opt in would be one boolean's worth of meaning on a form that
already has ten. So the tap rides along with the buttons: a notification that offers one
reports its tap too, and a notification that offers none is fire-and-forget.

`buttons` is a `@Multiline @Wired` "one per line" field sharing `optionsOf` with the
dialog nodes, so one field is both the typed list and the one wired in from a variable
or `transform.list_join`.

**Android renders three actions**, reply button included. The cut is announced in the
run log rather than silent, and it takes the reply field with it — `replyIndex` is
computed rather than "the last button", because attaching a `RemoteInput` to whatever
survived last would put a keyboard on somebody else's button.

## Being ignored is not an answer

Swiped away, replaced, or `timeoutSeconds` elapsed: **the branch does not fire.** A
macro that treated being ignored as a press would act on a decision nobody made — the
same fail-closed stance `PhoneRef` takes on an unresolvable contact, reached from the
other side.

There is no shape in `Fork` for "having decided to wait, do not pulse after all" —
`resume == null` says it up front and nothing says it later — so the lambda throws
`CancellationException`, which `WorkflowExecutor.awaitAndPulse` already handles by
rethrowing without pulsing and releasing the `PendingWaits` slot in its `finally`. The
`deleteIntent` exists for that release rather than for a third branch: without it a
swiped-away notification would hold one of the 64 slots until its timeout.

**`timeoutSeconds` is one field doing two jobs on purpose** — it sets `setTimeoutAfter`
*and* bounds the wait. Two fields could be set to contradict each other, leaving a
notification on screen that no longer reaches anything.

## The second branch is drawn only when it can fire

`notifyEffectivePorts` (`domain/registry/EffectivePorts.kt`) drops `resumed`, `button`,
`index` and `reply` unless `buttons` or `replyField` says the notification can be reacted
to. `dialogEffectivePorts`' rule applied to a whole branch: a port exists once it has
been asked for. The negative case is the load-bearing one — a plain notification still
looks exactly like the fire-and-forget node it was, which is what every macro built
before this expects.

**The rule itself is `notifyIsAnswerable`**, one function beside the constants, with two
callers: this resolver and the editor's `withoutStrandedAnswerBranch`. Written once
because the two disagreeing would leave a wire on a port that is no longer drawn. It
reads the **raw** config, so a blank value means blank — decoding would substitute the
property default and report buttons on a node that has none.

Two consequences, both of them traps `docs/ADDING_NODES.md` names:
`GraphEditorViewModel.retypesDataPorts` has to drop the stranded data edges, and
`withoutStrandedAnswerBranch` the exec one — `GraphValidator` resolves exec edges against
the **static** declaration, so nothing downstream would ever report a `resumed` wire left
behind by switching the last button off.

## Why a facade of its own

`SystemServices.notify(title, text)` used to do this and was **moved**, not extended.
`SystemServices` is write-only — every member changes something and none of them waits
for a reply — and `Notifications.await` waits. It is the boundary `Prompts` already
draws, for the reason `Prompts` gives.

`NotificationRequest` speaks neither `ValueType` nor `MacroAccent`: `core` cannot see
`domain`, so the accent crosses as a plain `0xAARRGGBB` from `MacroAccent.argb` with
**0 meaning "no colour of ours"**. Those are the `values-night` numbers deliberately — a
notification shade is dark on most phones out of the box, and there is no configuration
to resolve a day/night qualifier against for a surface this app does not draw.

## Identity is the tag, not the id

`manager.notify(tag, NOTIFY_ID, …)` with one fixed id: the **tag** is the identity, so
posting twice under one tag updates rather than stacks. That is what makes a progress
bar and `action.notify_cancel` possible at all. Before it the id was
`System.currentTimeMillis().toInt()` and a notification became unreachable the instant it
was posted. A blank tag becomes `node:<nodeId>`, and the `postedTag` output carries
whichever was used — so a cancel node can be wired to a notification whose tag nobody
typed.

`postedTag` rather than `tag`, which is the *input* of the same node: a generated string
key is `port_<typeId>_<portName>` with no direction in it, so two same-named ports on one
node collide there however legal the port rules find them.

## One channel, on purpose

Everything lands on `easymatic_default` at `IMPORTANCE_DEFAULT`. Importance is
channel-bound from Android 8, so a "how loud" field would mean a channel per level, five
Easymatic entries in Settings, and — worse — a channel whose importance the user then
edits silently overriding whatever the node says forever after. Worth doing deliberately
one day rather than as a side effect of this.

## The bits that fail silently

- **`FLAG_MUTABLE` on every `PendingIntent`.** `RemoteInput` works by the *system*
  writing the typed text into the intent; an immutable one arrives with an empty reply
  and no error anywhere. Safe because each names an explicit component in this app.
- **A distinct request code per button.** `PendingIntent` equality ignores extras, so two
  actions built with one request code are *one* `PendingIntent` — under
  `FLAG_UPDATE_CURRENT` the second overwrites the first's extras and both buttons report
  the same index. `NotificationResponses.nextRequestCode()` is the counter.
- **`register` is `getOrPut`, not an assignment.** It is called twice for every
  answerable notification — once as it is posted, once as the node begins to await — and
  the notification is on screen from the first, so a tap in between must complete
  something the second call then finds already done.

## `ForegroundGrant`

`AndroidSystemServices.canStartActivity()` refuses **without trying** unless the app
holds `SYSTEM_ALERT_WINDOW` or has a window on screen. That is right for a macro firing
off a geofence, where a silently-dropped `startActivity` would be reported as a success —
and wrong the moment the user has just tapped a notification, which is precisely the
interaction the platform allows a background start for. `NotificationResponseReceiver`
stamps a ten-second window; `canStartActivity` reads it as a third answer.

A stamp rather than a question because there is nothing to ask: the allowance is state
inside the activity manager with no public reader. It is **not a promise** — whether it
really applies varies by manufacturer — but when it does not, the outcome is the one we
had all along (`LaunchOutcome.Blocked`, with a line in the run log), so nothing
regresses.

## What a model gets

`action.notify` is the **only fork offered as an AI tool**. The fork exclusion in
`canRunAsTool` is about the inherited `run` being a stub, not about being a fork, so a
node that overrides `run` and says so through `ForkAction.runsWithoutAFork` is offered.
Posting is the whole job until somebody reacts, and reacting is not something a tool call
could carry back — a tool call has no graph to pulse into and a model cannot wait twenty
minutes. Buttons still appear; nothing waits for them.

## Known limits

- **A pending branch does not survive the process dying**, and dies when the macro is
  disabled — `ForkAction`'s stated cost. The notification may well outlive both, and
  tapping it then does nothing at all. `timeoutSeconds` is the honest bound. Making this
  durable means persisting a half-finished run, which the executor deliberately does not
  do.
- **No dismissal branch.** The `deleteIntent` exists and ends the wait; it does not route.
- **No picture.** `BigPictureStyle` was built and taken out again. If it comes back it
  needs a bounded decode — `ImageScalePlan`'s argument with an extra edge, because a
  notification crosses a binder into a transaction buffer of about a megabyte — and it
  can only take a `content://` ref, since the other spelling `ImageRef` accepts is a
  path through the file router and this facade holds no folder grant.
- **No full-screen alarm, no grouping, no lock-screen visibility, no DND category, no
  bubbles.** All reachable from here; none of them wired.

## Tests

`NotifyActionTest` (the fake `RecordingNotifications`), `NotifyPortsTest` (when the
branch is drawn), `NotificationRegistryTest` (membership, the permanent typeId, the tool
exception). What a fake cannot cover — that a notification appears, that `RemoteInput`
really carries the text back, that the lock screen shows it — needs a device.
