Use this value to decide whether an external audio output is available before
running an action. Select **Device type** to check a particular connection category.

- **Connected** is true when at least one matching output exists.
- It is false when none match; an unreadable audio subsystem yields no value.
- Playback does not need to be active.

## Working of Audio Device Connected

- The consuming node requests the current value.
- Android supplies its connected audio outputs.
- The value applies the selected device type and returns whether any output matches.

## Example: Continue Only With Bluetooth Audio

- Add Audio Device Connected as a value node.
- Select Bluetooth for Device type.
- Wire Connected into an If comparison against true.
- Connect the true branch to your audio action.

Explanation:
- A connected Bluetooth speaker or headset allows the true branch to run.
- Pairing alone does not count; Android must report a connected audio output.

## Device Categories

- Any includes all five external output categories below.
- Wired (AUX) includes wired headphones/headsets, analog line and AUX outputs.
- USB includes USB headsets, USB audio devices and accessories.
- Bluetooth includes classic audio, LE headsets/speakers, hearing aids and broadcast groups.
- HDMI / Digital includes HDMI, ARC/eARC, S/PDIF, IP audio, external buses and network speaker groups.
- Dock includes digital and analog audio docks.

## Points to Remember

- Built-in speakers, earpieces, microphones, virtual routes, tuners and unknown devices are excluded.
- Disconnecting one output leaves the value true if another matching output remains.
- Newer device types are supported when Android reports them.
- Use the Audio Device Connected trigger to react to connection changes.
- Existing workflows keep the `value.headset` node and `plugged` output identifiers.
- A node saved without a device filter defaults to Any.
