---
name: external-api
description: Read before touching the process API - trigger.api, ApiTriggerProvider, ApiTriggerReceiver, ApiCallers, ApiTokens, ApiInputs and the consent and App access screens.
---

# The process API

### Called from outside

`trigger.api` ("Called by Another App") is how another app, a script or a shortcut starts a macro. The author-facing guide is **`docs/EXTERNAL_API.md`**; what follows is why it is shaped this way.

**It replaces a hole rather than opening one.** `RunTriggerActivity` was `exported="true"` with no permission and no intent-filter, so any app on the device could already start any `trigger.manual` by component name — no opt-in, no identity, no record. It is now `exported="false"`, which shortcuts survive because the system starts a shortcut's intent *as the publisher* (`startActivitiesAsPackage`, our own uid), so being exported bought nothing that path needed.

**Two doors over one dispatcher, split by where identity exists.** `ApiTriggerProvider` is a `ContentProvider`: `call()` is synchronous, returns a `Bundle`, and gives us a system-verified `getCallingPackage()` — everything an AIDL would have bought, for one line at the call site and **no dependency on us at all**, where an AIDL costs the caller a `.aidl`, a binding and a `DeadObjectException`. `ApiTriggerReceiver` answers a broadcast, because the callers that need it most — Tasker, Automate, MacroDroid, `adb shell am`, Termux — cannot call a provider at all. A caller wanting a *result* has one door; a caller that is not an Android app has the other.

That split is also the whole authorization story. A provider call can be **recognised**, so it is gated on a caller allowlist with the signing certificate pinned at approval time and re-checked on every call (`ApiCallers.isApproved`, `PluginRepository`'s model in reverse, reusing `PluginPackages.signerOf` rather than re-deriving a digest that would fail by silently revoking everything). A broadcast carries **no sender identity whatsoever**, so it can only be **authenticated** — hence a per-trigger token, required there and merely sufficient on the provider. `ApiTokens.matches` treats a **blank expected token as never matching**, which is the single load-bearing line: a trigger with no key is not callable *by key*, only by an approved app, and the opposite reading would authorise every caller that omitted the extra.

**No custom permission**, and not for `BIND_PLUGIN`'s reason alone. A provider's `readPermission`/`writePermission` **do not apply to `call()`** — `ContentProvider.Transport.call` enforces nothing — so a manifest guard would be decorative and the in-code check is the gate by necessity. A `normal` custom permission would also be install-granted to anyone who asks and would additionally lock out `adb shell`, which cannot be granted one.

**`list` cannot be unlocked by a token**, deliberately: a token authorises one trigger where `list` enumerates them all, and a caller with no token is exactly who `list` exists for. To a token-only caller, "no such macro" and "wrong key" are the same `denied` answer, so the door is not an oracle for which macro ids exist; an approved caller gets the honest `not_found`/`disabled`.

**A disabled macro is refused**, which is where this parts company with a widget tap and a launcher shortcut — both of which run one. Somebody pressing a tile can see the macro is off and means it anyway; an app three processes away cannot. `disabled` is its own status rather than folded into `not_found` because the two call for different things, and it is only ever told to a caller that already proved itself. It is listed by `list` with `enabled = false` so a picker can grey it rather than hiding a macro the user can see.

**Approval is package-wide**, because `list` enumerates every API trigger — a per-macro grant would be a promise the consent screen could not keep. The consent screen is an Activity that **refuses any launch where `getCallingPackage()` is null**, i.e. anything but `startActivityForResult`, which is the only form the system vouches for; that is why the refusal Bundle carries a plain `Intent` and not a `PendingIntent`, which would run under our identity and leave the screen unable to name the app it is about.

The node is the app's only `adaptiveTriggerNode` — its data outputs are named by the user with the same `@Ports`/`PortSpec` editor `action.script` uses, one direction instead of two, so `apiTriggerEffectivePorts` walks no edges and needs no `visiting` guard. It uses `PortSpec.parse` rather than `parseOutputs`: a script with no result reads as broken, a trigger carrying no data is ordinary. The **token lives in the node's config**, which puts it in the workflow JSON (and so in Android's backup, and duplicated with the macro) — accepted because the dispatcher already loads that node, and answered by **Regenerate** in the form.

`ApiInputs` is the one reading of a caller's values, taking a `Map<String, String>` rather than a `Bundle` so it is JVM-testable, and its central rule is that **an absent name contributes no entry** — the port then behaves exactly as an unwired one, where a `0` would be indistinguishable from a caller that meant zero. An `ANY` port receives **text**, unlike `action.script`'s, because everything here has already been flattened by the transport; structure arrives as JSON and is read with `transform.json_read`, which is `action.http`'s road.

**`activate` never emits.** A call resolves `(macroId, nodeId)` straight to `runFromTrigger`, `ACTION_RUN_MANUAL`'s model, so none of `ManualTrigger.activeFlows`' problems recur and no arm is required. The returned flow is a bare `MutableSharedFlow` rather than `emptyFlow()` because an empty flow *completes*, and `MacroEngineService.arm` drops a job whose body finished — an api-only macro would read as unarmed the instant it was armed.

Android 12+ forbids a background foreground-service start and a third-party broadcast is on **no** exemption list, so `ApiRun.start`'s `appScope` fallback is the normal road on that door rather than the unusual one. That costs the service's protection against the process being reaped mid-run, which is why the docs send long macros to the provider.

