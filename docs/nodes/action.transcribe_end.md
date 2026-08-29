End Transcribing ends the microphone opened by Start Transcribing, sends what
it heard to an AI model, and puts the answer on the `answer` port. Leave the question
empty and you get a transcript.

- It has one field, **If it fails**. Everything else was decided on Start Transcribing.
- It also collects a listen that already ended on its own limit.
- The sound is discarded once it has been sent. There is nothing to clean up.

## Working of the Node

- The node ends the running listen and waits for the last fragment of sound, so nothing is
  cut off.
- A listen that already hit its limit is collected here instead. The sound was kept for
  exactly this.
- With **What to ask** empty the sound goes for transcription; with it filled in the sound
  and the question go to a chat model together.
- The reply lands on `answer`. Every failure lands on **If it fails** and the node still
  pulses `out`.

## Example: a hands-free note

- Add Start Transcribing with **Listen for at most** set to 60.
- Wire `out` into whatever should happen while it listens.
- Wire that into End Transcribing. Pick a **Model** and leave **What to ask** empty.
- Wire `answer` into Write File.

Explanation:

- The empty question makes this a transcript rather than a summary.
- The 60-second limit means the file still gets written even if the macro takes a detour
  and reaches this node late.

## When Nothing Was Started

- Reaching this node with no listen running puts **If it fails** on the port and writes an
  error to the console.
- That is reported rather than ignored, because a macro reaching a stop it never started
  is usually a graph running in an order its author did not expect.
- A listen that heard only silence is different. That writes one ordinary line to the
  console and is not an error.

## Points to Remember

- The `language` port carries the code the recogniser says it used, and is empty when
  nothing reported one — every AI transcription, and every phone older than Android 14.
- This node makes no choices at all. Start Transcribing picked the engine and the model,
  and this one collects whichever session is open.
- An empty question is a setting. It routes the sound to the service built for
  transcripts, which is cheaper and works on servers no chat model runs on.
- Claude cannot hear. Point **Model** at Gemini, OpenAI, OpenRouter or a self-hosted model.
- The reply is cut off at **Longest reply (tokens)**, reaches the port anyway, and says so
  in the console.
- Both nodes need the microphone permission, even though only the start node opens it.
