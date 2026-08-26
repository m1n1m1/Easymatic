Toggle Flashlight drives the camera torch and reports what happened on its `state` port.
It can set the torch on, set it off, or flip it to the opposite of whatever it is now.

- **State** is the whole of the configuration: **On**, **Off** or **Toggle**.
- The `state` output carries `enabled` (whether the torch is now lit) and `changed`
  (whether the system accepted the change).
- It needs the camera grant. Without it the torch does not light and `changed` is false.

## Working of the Node

- **On** and **Off** ask for that state outright, whatever the torch was doing.
- **Toggle** first reads the torch through the same channel `value.torch` uses, then asks
  for the opposite.
- The request goes to the first camera on the phone that has a flash unit.
- The node pulses `out` either way. A torch that would not light does not stop the macro.

## Example: a panic light on a long press

- Add your trigger, then a Toggle Flashlight with **State** set to **Toggle**.
- Wire the trigger into it.
- Wire `out` into a Delay of 500 ms, then into a second Toggle Flashlight set to **Off**.

Explanation:

- One button both lights and darkens, because Toggle reads before it writes.
- The second node is set to **Off** rather than **Toggle**, so the macro cannot leave the
  torch lit if the first flip was already a darkening.

## Why Toggle Is Only Here

The other device switches - Wi-Fi, Bluetooth, Auto-rotate - offer **On** and **Off**
only. Each of them writes a setting it is never told the previous value of, so a Toggle
there would have nothing to invert. The torch is the one that has a reader behind it.

## When the State Cannot Be Read

**Toggle** needs an answer to "is it lit right now?", and three things leave that
unanswered.

- The phone has no flash unit.
- Another app holds the camera.
- The app has only just started and Android has not reported a torch mode yet.

The node then touches the torch not at all, writes a warning to the console, and reports
`changed` as false. It does not guess a direction, because a guess is a coin flip that
looks like a working macro. **On** and **Off** are unaffected: they need no reading.

## Points to Remember

- `changed` is the field to branch on when it matters whether the torch actually moved.
  `enabled` is what the node asked for and got.
- The torch stays lit after the macro ends. Nothing turns it off for you.
- Reading the torch back is `value.torch`, which needs no permission of its own.
- A torch lit from the quick-settings tile is seen by **Toggle** exactly as one lit by a
  macro.
