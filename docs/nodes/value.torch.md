Flashlight on answers whether the camera torch is lit at the moment it is asked. Like
every value node it has no execution ports and is never pulsed.

- It is pulled, the instant before the node consuming it runs.
- It follows the torch whoever lit it: a macro, the quick-settings tile, another app.
- It is the read half of Toggle Flashlight, and what that node's **Toggle** option
  resolves against.

## Working of the Node

- Android publishes torch changes to Ottomatic as they happen, and the newest one is kept.
- The node hands that back, so the read is a field lookup rather than a camera call.
- The read is memoised per consuming node, so every port of one node sees one answer while
  a second consumer reads fresh.

## Example: only light the way in the dark

- Drop an If and set its **Source** to Flashlight on.
- Compare with "is false".
- Wire the `true` branch into a Toggle Flashlight set to **On**.

Explanation:

- The macro leaves the torch alone when it is already lit, so a second run does not
  darken a light somebody wanted.
- No edge is drawn to the value node. If reads it straight from the dropdown.

## When It Reads Nothing

The node answers nothing at all in three cases, and each of them means the same thing to
whatever reads it.

- The phone has no flash unit.
- Another app holds the camera, so the torch cannot be driven or observed.
- The app has only just started and Android has not reported a torch mode yet.

A comparison against nothing is false rather than a guess, so "if the torch is on" does
not fire. Toggle Flashlight's **Toggle** option does nothing at all in the same three
cases and says so in the console.

## Points to Remember

- The node needs no permission. Reading the torch costs nothing; only changing it needs
  the camera grant, and that belongs to Toggle Flashlight.
- Being off and being unreadable are the same answer to a comparison. Check the console
  when a branch never fires.
- There is no trigger for the torch. Android publishes no event worth arming a macro on,
  so ask this question inside a macro something else started.
- On a phone with several flash units the answer is whichever one spoke last, which is
  the same one Toggle Flashlight drives.
