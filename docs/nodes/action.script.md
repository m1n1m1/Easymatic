Run Script runs JavaScript inside a macro. You name the data going in and the data coming
out, and those names become real ports on the card.

- Each line in **Inputs** or **Outputs** is one port, written `name:TYPE`.
- Inside the script, an input is a variable of that name and an output is a property of
  the object you return.

## Syntax

```js
// Inputs:  city:TEXT
// Outputs: greeting:TEXT
return { greeting: "Good morning, " + city };
```

## Example: greet by city

Wire any Text into `city`. With `Vienna` on that port, `greeting` carries
`Good morning, Vienna`.

Explanation:

- Adding a line to **Outputs** adds a socket. Deleting the line removes it.
- A wrong type does not fail here. It fails at the wire, where the graph refuses the
  connection.

## Inside the Sandbox

The script is plain ECMAScript in an isolated V8 context. The language and its standard
library are there. Nothing else is:

- No `fetch` and no network. Use Make Web Request and pass the result in.
- No `setTimeout`, `setInterval` or any other timer.
- No `import` and no `require`.
- No access to the device, the workflow or any page.

`console.log` works and lands in the run log.

## Points to Remember

- Every run gets a fresh isolate. Globals do not survive, and two macros running the same
  script cannot see each other's state. Use a variable when something must persist.
- A script that throws, returns the wrong shape or hits the timeout does not stop the
  macro. Every output carries the **If it fails** value and execution leaves `out`.
- The timeout defaults to 2000 ms. An accidental `while (true)` would otherwise pin a
  thread for as long as the service runs.
- Editing **Outputs** retypes ports that may already be wired.
