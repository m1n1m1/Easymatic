# AI and audio

Four nodes, added 2026-08-28: `action.ai_transcribe` (a sound file in, an answer out), `action.ai_listen` (the microphone in, an answer out), and the `action.ai_listen_start` / `action.ai_listen_stop` pair for listening *while* the macro does something else. Every one of them calls `Ai.complete` with `AiRequest.audio`, lands a failure on its `fallback` and pulses `out`, and none has a tool switch — `conversationBody` renders a turn as text on all three protocols, so media reaches a model through `complete` and not through the tool loop, which is `action.ai_describe`'s reason unchanged.

**Sound is the first media where the *type* decides whether a request is sendable**, and that is what makes this more than `action.ai_describe` with a different facade. Every provider that sees pictures accepts the same four picture types, so an image's media type never gated anything and that node can hand any file to any model and let the server object. Nothing like that is true here: Claude has no audio content block at all, Gemini's inline set excludes `audio/mp4` — which is exactly what this app's own `action.record_audio` writes — and OpenAI's chat endpoint takes WAV and MP3 and nothing else while its transcription endpoint takes eight formats. Those are facts about the protocol, so `AiProtocol.audioProblem(request, target)` states them and the refusal happens **before the network**, naming the format and the fix. A guessed media type comes back as a 400 naming neither the file nor the reason.

## Two wires, one rule, no user-facing mechanism

`audioWireFor(request, protocol, target)` (`data/ai/AiProtocol.kt`) is the whole decision, in one file-level function on `combineInstructions`' precedent — pure, JVM-tested, needing no repository, key or network:

> A request with sound and **no prompt** goes to the transcription endpoint where the protocol has one. Anything else goes down the chat wire.

The user-visible fact is a product one — *do you want it transcribed, or do you want to ask something about it* — expressed by leaving **What to ask** empty or filling it in. No field names a mechanism, per the *no platform plumbing in user vocabulary* rule.

**Choosing the wire and judging the media type are one function because they are one question.** `audio/mp4` is refused by OpenAI's chat endpoint and accepted by its transcription endpoint, so asking "is this sendable?" before deciding "sent where?" would refuse exactly the file the second wire exists for. That is why `audioProblem` is deliberately **not** a sixth guard in `RoutingAi.resolve` — a test pins the `.m4a`-reaches-Whisper case, and it is the correction most likely to be re-proposed.

`transcriptionEndpoint` is non-null on `OpenAiProtocol.OpenAi` and `SelfHosted` only. **Not OpenRouter**, which does publish one — its body is JSON carrying a base64 `input_audio` object rather than OpenAI's multipart upload, so routing there would be a 400 dressed as a transcription failure; it has audio-capable chat models instead. Not Gemini, which publishes none on the developer API, so a blank prompt always lands on its chat wire and `GeminiProtocol.requestBody` substitutes the verbatim-transcription instruction there. **The substitution is in the protocol, never in the node** — filling the blank in earlier would send every plain transcription down the chat wire on every provider.

**A blank question has to reach the model as an instruction, not as an empty string.** `chatPrompt` and `TRANSCRIBE_INSTRUCTION` are shared by every chat renderer, and the shape of the bug they fix is worth keeping: the instruction began on Gemini alone, which was the only provider whose blank prompt reached a chat body at all — and then OpenRouter, which has audio-capable chat models and *no* transcription endpoint, started sending a clip beside an **empty** text part. A model given audio and nothing to do with it answers conversationally, and nothing anywhere reports that a transcript was not what came back. The wording forbids the framing as well as asking for the words, because models reliably reach for "Sure! Here is the transcript:" and a closing remark, which is not a transcript and is what a macro then mails to somebody.

**`standingInstruction` drops the profile's persona for a transcription**, which is the other half of the same fix. "Reply in German", "keep it to one line", "you are a terse assistant" are all good things to put on a profile and every one of them rewrites a verbatim transcript into something that is no longer one — with no field on the node to turn them off, because a node asking for a transcript asked no question at all. A *question* about the same clip keeps the persona, which is what it is for. The node's own instruction survives either way: that is the task, not the persona.

`SelfHosted` is what makes the second wire worth having: whisper.cpp, faster-whisper and LM Studio all serve `/audio/transcriptions` in OpenAI's shape, which is how a recording becomes text without leaving the user's network. Audio is the payload where that matters most.

**`RoutingAi.resolve`'s blank-prompt guard was relaxed to `prompt.isBlank() && audio.isEmpty()`.** A clip with no question is a real request; the refusal survives for a node with neither.

`AiTransport.postMultipart` is a third caller of the existing private `call()`, so it inherits the cancel-on-stop handler and the error-stream-first read. It **replaces** `Content-Type` rather than merging (every protocol's `headers()` sends `application/json`, which is a 400 on a multipart body) and writes the parts **straight to the socket** rather than assembling a string. Base64 is decoded in `RoutingAi`, not in a protocol, so `data/ai/` stays free of the Android runtime.

## The capture is WAV, and that is forced

`Microphone.capture(CaptureRequest): CaptureOutcome` is a new facade member rather than a `RecordingRequest`, because `RecordingRequest` takes a folder and a name and a live-listen clip has neither and should leave nothing behind. `AudioCapture` (`data/audio/`) uses **`AudioRecord`** where `AndroidMicrophone` uses `MediaRecorder`, and the destination forces it: `MediaRecorder` compresses to MPEG-4/AAC, which Gemini's inline set and OpenAI's chat wire both reject. Mono 16 kHz PCM is the one format every provider accepts and is what every speech model resamples to anyway. `WavHeader` writes the 44-byte RIFF header by hand — Android has no encoder for uncompressed audio, because uncompressed is what there is nothing to call for.

Three things about it are load-bearing:

- **The same `Mutex` guards `capture` and the recording session**, via a `capturing` flag beside `active` rather than a second kind of `Session` — a capture has no recorder to stop, no temp file to move and nothing to announce. `isRecording()` answers `active != null || capturing`, without which `value.recording` would say "no" while the system's recording indicator was lit.
- **Nothing reaches `TriggerBus`.** `trigger.recording_saved` says "here is a file" and there is no file.
- **Two bounds end the loop**: `CaptureLimits.MAX_SECONDS` (120) is the courtesy one, and the byte cap is the real one — this accumulates into the heap of the process holding every armed macro.

**The start/stop pair is `action.record_start` / `action.record_stop` for a clip that is never a file.** `Microphone.beginCapture` launches the read on the microphone's own scope — not the caller's, which is cancelled the moment the start node finishes — and `endCapture` completes a `CompletableDeferred` and **joins the job** before answering, because the loop checks the stop signal once per buffer and returning early would hand back a clip missing its last tenth of a second. A capture that ends on its **own limit** releases the microphone but keeps the sound in `pendingCapture`, so a stop node reached late still gets the minute that was recorded rather than silence.

**The pair divides asymmetrically and that is deliberate**: start carries only the two numbers bounding the listening, and the model, the question, the reply bound and the fallback all live on stop, where the answer comes out. A **Model** field on the start node would ask which model to bill at the moment there is nothing yet to send it, and two model fields on one pair can disagree with nothing on either card saying which won. `silenceSeconds` defaults to **0** on the start node and **3** on `action.ai_listen`, which is the same field meaning the same thing under different intents: a fixed span ends naturally on a pause, where a macro that said it will decide when to stop should not have that decision taken back. `askAbout` (`engine/action/AiListenSupport.kt`) is everything after "here is a clip", written once so `action.ai_listen` and `action.ai_listen_stop` cannot come to disagree about what silence means.

`silenceSeconds = 0` means **"do not stop early"** here, the opposite of `action.listen`, where 0 hands end-of-speech detection to the platform. There is no detector behind a raw microphone read, and the label says so. `action.ai_listen` declares `RECORD_AUDIO` (the recording family's shared constant) and **no `DeviceCapability`** — copying `action.listen`'s `SPEECH_RECOGNITION` would badge it in the Problems panel on phones that run it perfectly.

`Files.readBytes` grew a defaulted `maxBytes`, and `AudioLimits.MAX_MODEL_BYTES` is **4 MB** — a heap budget rather than a provider's limit, since the bytes, the Base64 string and the request body are all live at once inside `MacroEngineService`. `readBoundedBase64` now grows a `ByteArrayOutputStream` rather than pre-allocating the cap, and clamps to `FileLimits.MAX_BYTES_CEILING` because the bound arrives from a caller now. `mediaTypeOf` gained the audio extensions and **stopped meaning "the types every model accepts"** — it answers what the file *is*, and `audioProblem` answers whether a provider will take it.

## Looking up what a model can do

**Only one provider publishes it.**

| Provider | `GET {base}/models` publishes |
|---|---|
| OpenRouter | `architecture.input_modalities`, `output_modalities`, `supported_parameters`, `name` |
| Gemini | `supportedGenerationMethods`, `thinking`, token limits — no modality field |
| Anthropic | `id`, `display_name`, `created_at` |
| OpenAI | `id`, `created`, `owned_by` |
| OpenAI-compatible | ids (Ollama's `/api/show` and LM Studio's `/api/v0/models` publish capabilities, but each is one vendor's contract) |

So `AiModelInfo.modalities` is `Set<AiModality>?` and **null means "the provider does not publish it", never "the model takes nothing"**. That is `CapabilityStatus.UNKNOWN`'s stance and `PickerOptions`' degradation rule in a third place, and it is the one line of `AiModelIdChooserOverlay` worth not simplifying: `modalities.orEmpty().containsAll(filter)` would read silence as refusal and empty the chooser on four providers out of five. An empty `input_modalities` array is null too — a key published with nothing in it has said nothing.

`AiModelIdChooserOverlay` replaced the bare `DropdownMenu` because a row now carries a display name, an id and a modality line, and the filter chips need somewhere to live. It offers **Sound** and **Pictures** and not the other three: text is on everything so a chip would never narrow, and video and files reach no node so filtering on them would promise something the graph cannot keep. A row still *shows* all five — saying truthfully what a model does is a different job from offering to filter on it. It stays a chooser beside an editable field rather than a read-only picker, on `AiModelCatalog`'s standing argument.

`AiModels.ids` survives as a derived accessor so the change reached only the callers that wanted the new data. `AiModality` lives in `domain/model/` because the picker in `feature/` reads it.
