---
title: Scripting
description: The Run Script node — what JavaScript it runs, and exactly what is and is not there.
sidebar:
  order: 5
---

**Run Script** is the graph's escape hatch: JavaScript over inputs you name, returning
values on ports you name. Everything else in the palette has a fixed meaning; this covers
what a palette never can — arithmetic over two readings, pulling a code out of an SMS,
reshaping an API response.

It runs on the **V8 inside the device's system WebView**, so no interpreter ships in the
app.

## Declaring ports

A script's shape is known only to whoever wrote it, so **both sides are named**, one per
line:

```
Inputs               Outputs
level:WHOLE_NUMBER   message:TEXT
body:TEXT            code:TEXT
response             items:TEXT[]
```

Each input becomes a JavaScript variable of its own name. The script returns an object
keyed by the output names.

- A port may name a type — `TEXT`, `NUMBER`, `WHOLE_NUMBER`, `YES_OR_NO`, `DATE_TIME` —
  which gives it a colour and a real type check, so a mis-wired script is a **refused
  drop**.
- A port with **no type** takes *anything*, which is what lets a whole HTTP response
  arrive as a real JavaScript object. No named type can say "object".
- `[]` after the type makes it a **list**.
- A script that reads nothing has no input handles at all, rather than unused wildcards.

Editing either list re-checks every wire touching the node and drops only those whose port
is gone or no longer type-checks.

```js
// inputs: level (WHOLE_NUMBER), body (TEXT)
const code = body.match(/\b(\d{6})\b/)?.[1] ?? "";
return { message: `Battery ${level}% — code ${code}`, code };
```

## What a script may use

**The ECMAScript standard library and nothing else.** Present and tested:

`Math`, `JSON`, `Date`, `RegExp`, `Promise`, `Map` / `Set` / `WeakMap`, `Symbol`,
`Proxy`, `Reflect`, `BigInt`, `Intl` — and current V8 syntax, verified through ES2023
(`toSorted`, `findLast`, `Object.hasOwn`, `at`, `replaceAll`, named capture groups,
optional chaining, classes, generators, destructuring).

**Absent, deliberately:**

- every web API — `fetch`, `XMLHttpRequest`, `WebSocket`, `crypto`, `TextEncoder`, `URL`,
  `atob`, `navigator`, `document`, `localStorage`, `performance`, `structuredClone`;
- **all timers** — `setTimeout`, `setInterval`, `queueMicrotask`;
- any module system — `require`, `module`, `import`.

No imports, no npm, no I/O. A script is one self-contained snippet over the values it is
handed.

:::caution
No timers means `async` is a trap rather than a feature. It compiles, but there is nothing
to await on, and a returned Promise is serialised to `{}` rather than resolved. **Scripts
are effectively synchronous.**
:::

Need the network? Use an **HTTP Request** node and wire its response in.

## A script cannot remember its previous run

Every run gets a **fresh isolate**, which is also how a runaway loop is stopped. That is
what [variables](/docs/concepts/variables/) are for.

## `console.log`

`console.log` output is collected and delivered to the macro's
[console](/docs/concepts/console/) at Info level.

It is drained even when the script then **fails or times out**, which is the whole point
— a line saying where it got to is exactly the case the feature exists for. Capped at 100
lines, because a runaway `console.log` emits tens of thousands inside one timeout.

Line and column numbers are dropped, because the script is inlined into one physical line
before it runs and the platform's numbers would be offset by a constant.

## Failure

**Run Script** is an action, not a transform. It is cross-process work that can throw, can
time out, and on a device with no usable WebView cannot happen at all.

Every failure lands on the node's **If it fails** fallback and pulses `out` rather than
halting the macro. Acting on "the script failed" is an *If* on its output, which is
visible on the canvas.

**Timeout** is a config field, in milliseconds.

## What a script cannot do

A script cannot call anything on the app's side or read a reply. The isolate has no DOM,
no network and no filesystem, and nothing of Easymatic's is exposed to it.

That is deliberate rather than unfinished: an effect a script performs from a text field is
invisible on the canvas — the same rule that means there is no way to attach a condition
to a node.
