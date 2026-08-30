# Calling Easymatic from another app

Easymatic can run a macro when another app, a script or a shortcut asks it to. This
document is for whoever writes the caller.

Nothing here needs a library, an AIDL file or a dependency on Easymatic.

## The two doors

| | `ContentProvider.call()` | Broadcast `Intent` |
|---|---|---|
| Reachable from | an Android app | anything — Tasker, Automate, MacroDroid, `adb`, Termux |
| Returns | a `Bundle` | nothing |
| Identifies you | yes, `getCallingPackage()` is verified by the system | no — a broadcast carries no sender at all |
| Authorised by | the user approving your app, **or** a key | a key, always |
| Can list the available triggers | yes | no |

Use the provider if you are an app. Use the broadcast if you are a script.

## Setting up, on the Easymatic side

A macro is reachable **only** if the user has placed a **Called by Another App**
trigger on it. There is no way for a caller to make a macro reachable, and nothing is
reachable by default.

That node has three settings:

- **Name other apps see** — what your picker should show. Falls back to the node's
  name, then the macro's.
- **Values this call carries** — the inputs, one per row, each with a name and a type.
  These become the trigger's output ports in the graph.
- **Key** — generated when the node is placed, with Copy and Regenerate beside it.
  Clearing it means *approved apps only*: the macro becomes unreachable by key, which
  is the stricter setting and the right one if no script needs it.

## Calling it as an app

```kotlin
val uri = "content://io.github.m1n1m1.easymatic.triggers".toUri()

// 1. Are we allowed?
val listed = contentResolver.call(uri, "list", null, null)
if (listed?.getString("status") == "needs_approval") {
    // A plain Intent, not a PendingIntent — start it FOR RESULT or it will refuse.
    startActivityForResult(listed.getParcelable("approvalIntent")!!, REQUEST_APPROVE)
    return
}

// 2. What is there?
val payload = listed!!.getString("payload")!!   // JSON, see below

// 3. Run one.
val result = contentResolver.call(
    uri, "run", macroId,
    bundleOf("nodeId" to nodeId, "in.city" to "Vienna", "in.count" to 3),
)
result?.getBoolean("ok")      // true when the run has started
result?.getString("status")   // "started", or why not
```

`startActivityForResult` is required for the approval step and is not a style
preference: `getCallingPackage()` is non-null only for that form, so it is the only
way the consent screen can name your app. Easymatic refuses a plain `startActivity`.

### Methods

| Method | `arg` | Extras | Needs approval |
|---|---|---|---|
| `version` | — | — | no |
| `list` | — | — | **yes** |
| `run` | the macro id | `nodeId`, `token`, `in.*` | approval **or** a matching `token` |

`list` cannot be unlocked with a key, deliberately: a key authorises one trigger where
`list` describes all of them, and a caller with no key yet is exactly who `list` is
for.

### What `list` answers

```json
{
  "version": 1,
  "triggers": [
    {
      "macroId": "3f2b…", "macroName": "Morning",
      "nodeId": "a91c…", "label": "Start the coffee",
      "enabled": true, "token": "Yx3…",
      "inputs": [
        { "name": "city",  "type": "TEXT",         "list": false },
        { "name": "count", "type": "WHOLE_NUMBER", "list": false },
        { "name": "tags",  "type": "TEXT[]",       "list": true  }
      ]
    }
  ]
}
```

Types are `TEXT`, `NUMBER`, `WHOLE_NUMBER`, `YES_OR_NO`, `DATE_TIME`, or `ANY` for an
untyped port. `enabled` is the macro's switch — a disabled macro **is listed**, so you
can grey it out rather than silently omitting something the user can see in Easymatic.

### Statuses

| `status` | Meaning |
|---|---|
| `started` | The run is under way. It has **not** finished. |
| `needs_approval` | Start `approvalIntent` for a result and ask again. |
| `denied` | Wrong key — or no such macro. The two are the same answer to a caller holding only a key, so this door cannot be used to discover which macro ids exist. |
| `not_found` | No such macro, or no single API trigger in it. Only told to an approved caller. |
| `disabled` | The macro exists and you may run it, but its switch is off. |
| `invalid` | Malformed — no macro id, unknown method, ambiguous macro. |
| `rate_limited` | Too many calls. The bucket refills; nothing is revoked. |

`run` answers as soon as the run **starts**, never when it ends. A macro may contain a
delay or a *Wait Until*, and a binder call cannot sit on either.

## Calling it as a script

```bash
adb shell am broadcast \
  -a io.github.m1n1m1.easymatic.action.RUN_MACRO \
  -p io.github.m1n1m1.easymatic \
  --es macroId 3f2b… \
  --es token Yx3… \
  --es in.city Vienna --es in.count 3
```

**Always set the package** (`-p`, or `setPackage` in code). It makes the intent
explicit, which is what keeps the key from being readable by every app on the phone.

`nodeId` is optional: a macro with exactly one **Called by Another App** trigger
resolves without it. A macro with two is refused rather than guessed at.

Nothing is answered on this path. A wrong key, a missing macro and a switched-off
macro are indistinguishable from the outside; they differ only in Easymatic's log.

## Inputs

Every value is sent as an extra prefixed `in.` — `in.city` fills the port named
`city`.

- Only declared names are read; anything else is ignored.
- **An absent name is not "empty"** — the port behaves exactly as if nothing were
  wired to it, and the macro falls back the way it normally would.
- Values are coerced to the declared type and never fail the call: `in.count=banana`
  arrives as `0`.
- A **list** port reads a JSON array (`in.tags=["a","b"]`). A plain value becomes a
  one-element list.
- An **`ANY`** port receives text. Send JSON and read it in the macro with a
  *Read JSON* node if you need structure.
- One value may not exceed 256 KB. An oversize value is dropped, not truncated.

## Things worth knowing

**The macro must be switched on.** This is where an external call differs from a
widget tap or a launcher shortcut, both of which run a disabled macro: somebody
pressing a tile can see it is off and means it anyway, and you cannot.

**Long macros: prefer the provider.** Android 12 and later forbid starting a
foreground service from the background, and a third-party broadcast is on no exemption
list — so a macro started that way runs without the foreground service protecting it
from the process being reaped. Short macros are unaffected.

**Approval is package-wide**, covering every macro that carries the trigger — not one
macro. The user can withdraw it at any time under **App access** in Easymatic, and an
app re-signed by a different developer is revoked automatically.

**Rate limits** are a token bucket: a burst of 10, then 30/minute. Every anonymous
broadcast caller shares one bucket; each identified app has its own.

**The key travels in the clear over IPC.** That is safe against other apps because the
intent is explicit and the provider call is point-to-point, but a key checked into a
public repository or written into a shared script is a key anybody can use. Regenerate
it in the node's config if that happens.
