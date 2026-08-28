Transcribe Audio with AI reads a sound file from the phone and puts an AI model's answer
about it on the `answer` port. Leave the question empty and you get a transcript; fill it
in and you get an answer about what was said.

- **Audio file** takes a path, and is wired far more often than typed. The recording worth
  transcribing is usually the one `action.record_audio` just made.
- **What to ask** is the only switch that matters. Empty means transcribe.
- Files are read up to 4 MB. A larger one is refused by name, not truncated.

## Working of the Node

- The node reads the file through the same file layer every `action.file_*` node uses, so
  a folder you granted months ago still works at three in the morning.
- It works out what the file is from its extension. An extension it does not recognise
  stops the run there, before anything is sent.
- With **What to ask** empty, the clip goes to the model provider's transcription service
  where one exists, and comes back as text.
- With **What to ask** filled in, the clip and the question go to the chat model together.
- Whatever comes back lands on `answer`. Every failure lands on **If it fails** and the
  node still pulses `out`.

## Example: mail yourself a transcript of a voice note

- Add a Record Audio node with **Seconds** set to 30.
- Add Transcribe Audio with AI and wire Record Audio's `state` output into **Audio file**
  through a Break Struct on the `path` field.
- Pick a **Model**, leave **What to ask** empty.
- Wire `answer` into a Send Mail node's body.

Explanation:

- The empty question is what makes this a transcript rather than a summary.
- The path is wired rather than typed, because Record Audio chooses the file name.

## Which Providers Can Hear

Audio support varies more than picture support does, and the node says so before it
sends anything rather than after.

- **Gemini** takes WAV, MP3, AIFF, AAC, OGG and FLAC. It does not take `audio/mp4`, which
  is what this app's own Record Audio node writes. Convert the file, or use Listen with
  AI, which records WAV.
- **OpenAI** transcribes almost any format when the question is empty. With a question
  filled in it takes WAV and MP3 only, and needs a model that accepts audio.
- **A self-hosted server** transcribes when it serves `/audio/transcriptions`, which
  whisper.cpp, faster-whisper and LM Studio all do. Nothing leaves your network.
- **OpenRouter** needs a model that accepts audio, and always takes the question path.
- **Claude** cannot hear at all. The node refuses with a sentence saying so.

## Points to Remember

- A blank question is a setting, not a missing field. It routes the clip to the service
  built for transcripts, which is cheaper and available on servers no chat model runs on.
- An unrecognised extension is refused with the file named. Rename it rather than
  guessing at the model.
- 4 MB is about seventeen minutes of voice recording. The limit protects the phone's
  memory, not your quota.
- A transcript longer than **Longest reply (tokens)** is cut off, reaches the port anyway,
  and says so in the console.
- The node has no tool switch. Sound reaches a model on the single-question path only.
