Start Listening with AI opens the microphone and returns straight away, so the macro can
carry on while it listens. Nothing is sent anywhere until Stop Listening with AI collects
the sound.

- It carries no **Model** and no question. Those live on the stop node, where the answer
  comes out.
- **Listen for at most (seconds)** is a limit, not a length. It is required.
- **Stop after this much silence** defaults to 0 here, meaning do not stop early.

## Working of the Node

- The node claims the microphone. If a recording or another listen is already running it
  reports that and changes nothing, because there is one microphone.
- Reading runs in the background. The node pulses `out` immediately, whether it started or
  not.
- The sound accumulates in memory. Nothing is written to storage at any point.
- When the limit runs out the microphone is released and the sound is **kept**, so a stop
  node reached later still gets what was recorded.
- Stopping the macro releases the microphone.

## Example: listen while a dialog is open

- Add Start Listening with AI. Set **Listen for at most** to 30.
- Wire `out` into an Ask Yes or No dialog reading `Recording. Done?`
- Wire the dialog's `confirmed` port into Stop Listening with AI.
- Pick a **Model** on that node, leave **What to ask** empty, wire `answer` into a
  notification.

Explanation:

- The dialog can only be shown because this node returns instead of blocking. Listen with
  AI would have held the run for the full 30 seconds first.
- The 30-second limit is a backstop. If nobody taps the dialog, the microphone still closes.

## Why the Model Is on the Other Node

- Nothing is asked of a model here. This node only opens the microphone.
- A **Model** field would ask which model to bill at the moment there is nothing yet to
  send it.
- Two model fields on one pair of nodes can disagree, and nothing on either card would
  say which one won.

## Points to Remember

- Zero silence means listen the whole time. That is the opposite of the same field on
  Listen, which hands the decision to the phone.
- A second Start while one is running reports that and leaves the first alone.
- Two minutes is the ceiling however large a number you type.
- The system's recording indicator stays lit for the whole span. That is Android, not the
  app.
- Use Listen with AI instead when the macro has nothing to do while it listens. One node
  is simpler than two.
