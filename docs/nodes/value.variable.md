Variable reads what a variable holds at the moment it is asked. Like every value node it
has no execution ports and is never pulsed.

- It is pulled, the instant before the node consuming it runs.
- Its output takes the type the variable was declared with.

## Working of the Node

- **Variable** points at a declared variable by id, not by name.
- The value is read when a consumer asks for it.
- The read is memoised per consuming node, so every port of one node sees one value while
  a second consumer reads fresh.

## Comparing a Variable

If can compare a value node with no wiring: you pick it from a dropdown and no edge
appears. That shortcut passes no configuration, so it cannot say which variable you
meant. This node is therefore left out of the dropdown, because offering it would offer a
comparison that silently never matched.

To compare a variable, drop the node on the canvas and wire it into an If's `source`
port.

## Example: branch on a counter

- Drop a Variable node and pick the counter in **Variable**.
- Wire its output into an If's `source` port.
- Set the If to "greater than", comparing against `3`.

## Points to Remember

- A variable must be declared before anything reads it. Reading an undeclared or deleted
  one is a warning, not an error: the node logs that it read nothing and the graph carries
  on.
- Renaming a variable does not break this node, because it points at an id.
- A local variable belongs to one run of one macro. A global outlives both.
- Reading a local from a macro that never set it gives nothing, which usually means the
  read and the write ended up in different macros.
