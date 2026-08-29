Start Transcribing opens the microphone and returns straight away, so the macro can
carry on while it listens. Nothing is sent anywhere until End Transcribing collects
the sound.

- It carries no **Model** and no question. Those live on the stop node, where the answer
  comes out.
- **Listen for at most (seconds)** is a limit, not a length. It is required.
- **Stop after this much silence** defaults to 0 here, meaning do not stop early.
- **Language** offers the phone's language, automatic detection, or one you choose — the
  same three answers Transcribe gives, and it is set here because this node opens the
  session.

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

- Add Start Transcribing. Set **Listen for at most** to 30.
- Wire `out` into an Ask Yes or No dialog reading `Recording. Done?`
- Wire the dialog's `confirmed` port into End Transcribing.
- Pick a **Model** on that node, leave **What to ask** empty, wire `answer` into a
  notification.

Explanation:

- The dialog can only be shown because this node returns instead of blocking. Listen with
  AI would have held the run for the full 30 seconds first.
- The 30-second limit is a backstop. If nobody taps the dialog, the microphone still closes.

## Everything Is Set Up Here

- The whole pair is configured on this node: the engine, the model, the question, the
  language and the two limits. End Transcribing only collects the result.
- That is the same shape as Record Audio's family, where Start Recording holds every field
  and Stop Recording holds none.
- **Model**, **What to ask** and **Longest reply** appear only when **Transcribe with** is
  set to an AI model. On the phone engine they are not shown, and nothing asks you for them.
- **Language** appears only on the phone engine, because an AI model works the language out
  for itself.

## Points to Remember

- Zero silence means listen the whole time. That is the opposite of the same field on
  Listen, which hands the decision to the phone.
- The phone's recogniser hears one utterance at a time, so a long session is many short
  ones joined up. You will not see that happen; it is why a pause does not end the session.
- A very long pause with nothing said at all can still end it on some phones. The AI engine
  records straight through regardless.
- A second Start while one is running reports that and leaves the first alone.
- Two minutes is the ceiling however large a number you type.
- The system's recording indicator stays lit for the whole span. That is Android, not the
  app.
- Use Transcribe instead when the macro has nothing to do while it listens. One node
  is simpler than two.
