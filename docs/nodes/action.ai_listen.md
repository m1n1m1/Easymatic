Listen with AI opens the microphone, sends what it heard to an AI model, and puts the
model's answer on the `answer` port. Nothing is written to storage at any point.

- **Listen for at most (seconds)** is required and is capped at 120 by the app.
- **Stop after this much silence** ends the clip early once somebody has spoken. Zero
  listens for the whole time.
- **What to ask** empty means transcribe. Filled in, it asks about what was said.

## Working of the Node

- The node claims the microphone. If a Start Recording is already running it reports that
  and stops there, because there is one microphone.
- It records mono 16 kHz WAV into memory, watching how loud each moment is.
- Once it has heard speech, a stretch of quiet as long as **Stop after this much silence**
  ends the clip. Quiet before the first word does not count, so a slow speaker is not cut off.
- The clip goes to the model, and the reply lands on `answer`.
- The clip is discarded either way. There is no file to clean up.

## Example: a spoken yes-or-no gate

- Add Listen with AI. Set **Listen for at most** to 8 and **Stop after this much silence**
  to 2.
- Set **What to ask** to `Answer only YES or NO: did the speaker agree?`
- Wire `answer` into an If node comparing against `YES`.
- Wire the If node's `true` branch into whatever the agreement should do.

Explanation:

- The question turns a recording into a decision, which a plain transcript would still
  need parsing to reach.
- Two seconds of silence ends the clip as soon as the speaker stops, so the macro does not
  wait out the full eight.

## How It Differs From Listen

Both nodes hear one spoken answer. They use different ears, and the choice is real.

- **Listen** uses the phone's own recogniser. It is free, it can work with no network, and
  it only knows the languages this phone shipped with.
- **Listen with AI** sends the sound to a model. It costs a request, it needs a network,
  and it understands any language the model does.
- Only this node can answer a question. Listen returns words; this returns whatever you
  asked for.
- Listen routes on three exec ports for heard, timed out and nothing. This node has one
  `out` port and puts **If it fails** on the port when nothing was said.

## Why It Records WAV

- Record Audio writes MPEG-4, which suits a file you keep and mail to somebody.
- Gemini refuses that format outright, and OpenAI's question path takes WAV and MP3 only.
- Mono 16 kHz WAV is accepted by every provider that can hear, so this node works on any
  connection that works at all.
- The format costs about 32 KB per second, which is why the length is capped.

## Points to Remember

- Nothing was said is not a failure. The node writes one line to the console and puts
  **If it fails** on the port, so a macro listening on a schedule does not fill the console
  with errors overnight.
- The microphone grant is the same one the Record Audio nodes use. Granting it once covers
  all of them.
- The system's recording indicator is lit for the whole clip. That is Android, not the app.
- Claude cannot hear. Point **Model** at Gemini, OpenAI, OpenRouter or a self-hosted model.
- Stopping the macro mid-clip releases the microphone. No recording is left half-open.
- This node holds the run for the whole span. Use Start Listening with AI and Stop
  Listening with AI when the macro has something to do while it listens.
