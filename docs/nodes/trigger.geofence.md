Geofence starts a workflow when the phone crosses the edge of a saved circular area, such
as home or the office. It can also fire after you have been inside one for a while, or
away from one for a while.

- The area comes from the places library. The node only points at it.
- Four switches decide which crossings it listens for.

## Working of the Trigger

- Android watches the circle and reports enter, exit and dwell.
- Away is this app's own timer, started when you leave.
- Only the switches you turn on are registered.
- The `transition` field of `event` says which of the four fired.

## The Four Events

- **On enter** fires as you cross in. It is the only switch that starts on.
- **On exit** fires as you cross out.
- **On dwell** fires after **Dwell delay**, which defaults to 30 seconds. Use it to tell
  "arrived" from "drove past".
- **On staying away** fires after **Away for** minutes, which default to 30. Exit is the
  moment of leaving; away is the state of having been gone a while.

Note: with all four switched off the node registers nothing and can never fire. The
editor warns about it.

## Permissions

Location access is the obvious grant. **Location access set to "Allow all the time"** is
a second one, is not offered in the first dialog on modern Android, and has to be turned
on in Settings.

Without it the geofence is never registered. Nothing crashes and nothing is logged, so it
looks identical to a geofence that is waiting. That is why the node badges itself in the
Problems panel.

## Example: lights on when you get home

- Save a place called Home in the places library.
- Point **Place** at it and leave **On enter** on.
- Wire `out` to the light action.

Explanation:

- To catch arriving rather than driving past, switch **On enter** off and **On dwell** on.
- Moving house means editing the place once. Every macro that references it follows.

## Points to Remember

- Arming a macro while the phone is already inside the area fires **On enter** at once.
- Android trades promptness against battery, so expect a lag of up to a couple of minutes.
- Pick a radius comfortably larger than the accuracy in the event. A hundred metres is
  usually the smallest that behaves.
- The `away` event carries no position. Its `latitude`, `longitude` and `accuracyMeters`
  are empty.
