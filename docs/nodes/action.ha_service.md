Call Home Assistant Service runs any service the hub exposes: a thermostat, a media
player, a lock, a vacuum, one of your own scripts.

- The form is built from the hub, so it differs for every service.
- A failed call is reported on `state`. It does not halt the macro.

## Working of the Node

- Pick a hub, an entity and a service.
- The hub is asked what that service accepts, and the matching rows appear underneath.
- On run, the service is called with those rows plus anything in **Extra data (JSON)**.
- `out` pulses either way, and `state` says what happened.

## The Form Is Built From the Hub

Choose `light.turn_on` and you get brightness and colour. Choose
`climate.set_temperature` and you get a temperature. Two consequences:

- The Configuration table above lists only the fields the node declares, so the
  service-specific rows are missing from it.
- With the hub unreachable, the choosers are empty and no extra rows appear.

## Why Hub, Entity and Service Are Pickers

A hub publishes a complete list of its entities and services, so the chooser covers every
possible answer and a typed id could reach nothing extra. Event types in the other Home
Assistant nodes are typed, because Home Assistant publishes no way to list them.

## Example: dim the hall light

- Pick the hub, then the hall light as **Entity**, then `light.turn_on` as **Service**.
- Set the brightness row that appears underneath.
- Break out `state` and branch on `called` with an If.

Explanation:

- The brightness row is not declared by the node. It came from the hub's description of
  `light.turn_on`.
- Branching on `called` is how a macro notices the hub was unreachable.

## Points to Remember

- `state` carries `service`, `target`, `called`, `response` and `error`.
- **Extra data (JSON)** is merged into the call and is also available as the `data` port.
- The service-specific rows are read while you configure the node, not when the macro
  runs.
