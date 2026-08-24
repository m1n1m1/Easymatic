If is the only decision-making node in Ottomatic. It compares two values and sends
execution down one of two paths.

- The comparison runs when execution reaches the node.
- Exactly one output pulses, `true` or `false`.

## Working of If

- Read the left-hand side from the **Source** field, or from the `source` port.
- Read the right-hand side from **Compare against**, or from the `value` port.
- Apply the **Operator**.
- Pulse `true` if the test passes, `false` if it does not.

## Choosing the Source

Pick a value node in **Source** and it is read on demand. Nothing is wired and nothing
extra is placed on the canvas.

Wire the `source` port instead when the value is produced by the graph, such as a field
broken out of a trigger's event. A connected wire always wins.

## Example: run only while the battery is low

- Set **Source** to the Battery Level value.
- Set **Operator** to "less than".
- Set **Compare against** to `20`.
- Wire the guarded work to `true`, and leave `false` unconnected.

Explanation:

- The battery is read at the moment the If runs, not when the macro started.
- An If with only `true` connected works as a gate.

## Conditions Are Not Attached to Nodes

"Run this only when X" is written as an If in front of the thing being guarded, including
in front of a trigger's branch. Per-node conditions existed in an earlier build and were
removed, because nothing on a card showed that one was attached.

## Points to Remember

- A comparison against something missing is false, not an error. The macro keeps running
  down the `false` branch.
- A source that is neither a value node nor a connected wire fails closed.
- Ordering comparisons parse both sides first. ISO-8601 text does not sort
  chronologically across time-zone offsets. Equality still compares text.
- Branches that split and rejoin run the joining node once per incoming pulse.
