Use this trigger to react to external speakers, headphones or audio docks becoming
available. Choose **Device type** to limit which connections start your workflow.

- **Event** selects Connected, Disconnected or Any.
- Devices already connected when monitoring starts do not fire an event.
- Use the Audio Device Connected value node to check the current connection state.

## Working of Audio Device Connected

- Android reports an audio output being added or removed.
- The trigger classifies the output and applies your device and event filters.
- A matching change starts the workflow and provides a State item.
- The item includes the device name in `detail` and the event timestamp.

## Example: React to a Bluetooth Speaker

- Add Audio Device Connected as a trigger.
- Select Bluetooth for Device type and Connected for Event.
- Connect the trigger to the actions you want to run, then enable the workflow.
- Connect a Bluetooth speaker with media audio enabled.

Explanation:
- The speaker starts the workflow when Android reports its audio output.
- Connecting a USB headset does not match this filter.

## Device Categories

- Any includes all five external output categories below.
- Wired (AUX) includes wired headphones/headsets, analog line and AUX outputs.
- USB includes USB headsets, USB audio devices and accessories.
- Bluetooth includes classic audio, LE headsets/speakers, hearing aids and broadcast groups.
- HDMI / Digital includes HDMI, ARC/eARC, S/PDIF, IP audio, external audio buses and network speaker groups.
- Dock includes digital and analog audio docks.

## Points to Remember

- Only devices Android reports as audio outputs count. Built-in speakers, earpieces,
  microphones, virtual routes, tuners and unknown devices are excluded.
- Monitoring works while the app's engine process is running.
- Each reported output has its own event. Hardware exposing multiple outputs can fire more than once.
- Disconnecting one device still fires if another remains connected.
- Newer device types are supported when Android reports them.
- Existing workflow identifiers remain compatible: `trigger.headset`, with State
  `event` values `plugged` and `unplugged` for Connected and Disconnected.
