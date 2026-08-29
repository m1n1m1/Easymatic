Translate turns text into another language and puts the result on the `translation`
port. It runs on the phone, so it needs no AI account, no key and no signal.

- **Translate from** defaults to working the language out from the text itself, which is
  what you want when the text arrives from a message, a notification or a transcript.
- **Translate to** has no default. A blank one is an error, not "the phone's language".
- Both fields offer only the languages you have **added** under Setup, Translation
  models. The node never downloads anything itself.

## Working of Translate

- The node reads **Text**, which is wired far more often than typed. The text worth
  translating is usually the one a trigger just handed you.
- With **Translate from** left at *Detect automatically*, the text is examined and a
  language is chosen. Text too short to judge — a name, an emoji, `OK` — is reported
  rather than guessed at.
- Both languages are checked against what is on the phone. One whose model has been
  removed ends the run there, with a message naming it.
- The translation lands on `translation`. Every failure lands on **If it fails** and the
  node still pulses `out`.
- Text that is already in the target language comes back unchanged. That is a success,
  not a failure, and it works even with nothing added at all.

## Example: read a foreign message aloud in your own language

- Open Setup, Translation models and add your own language plus the one you expect to
  receive.
- Add a Message Received trigger and pick the messenger you want.
- Add Translate and wire the trigger's message text into **Text**.
- Leave **Translate from** on *Detect automatically* and set **Translate to** to your own
  language.
- Wire `translation` into a Speak node.

Explanation:

- Detection is doing the work here. You do not know in advance what language a message
  will arrive in, so a fixed source language would be wrong most of the time.
- Speak reads whatever it is given, so the phone says the message in your language while
  the sender wrote it in theirs.

## Adding Languages

Translation runs on a model per language, and you choose which ones the phone keeps.

- Setup, then Translation models, then **Add a language**. Each is about thirty megabytes
  and downloads there and then.
- English is built in and is never listed. Everything else is a download.
- Translation goes **through English**, so German to French needs German *and* French
  added.
- The language chooser on the node links straight to that screen, so you can add one
  without hunting for it — but it leaves the editor, so add what you need before you wire
  a graph up.
- Deleting a language stops every macro that translates into or out of it, until you add
  it back.

## Points to Remember

- **The node never downloads.** A macro that fires at three in the morning will not start
  a thirty-megabyte transfer on your mobile data, and will not sit waiting for Wi-Fi that
  never comes.
- Because the fields only offer what is added, a node you were able to configure is a node
  that can run. The one way to break it is to delete a language it was using — and then
  the message names that language.
- **Translate to** blank is an error the node reports. It never falls back to the phone's
  language, because a macro would then do something different on somebody else's phone.
- Detection needs something to work with. One or two words often cannot be judged, and the
  node says so rather than picking wrongly.
- Detection itself needs nothing added — it will happily name a language you have not
  installed, and the node then tells you that is what happened.
- Nothing here reaches the network at all. The translation never leaves the phone, which
  is the reason to use this rather than Ask AI.
- Nothing halts. A refusal lands on **If it fails** and `out` pulses anyway, so compare the
  output with an If node when a failed translation should change what happens next.
