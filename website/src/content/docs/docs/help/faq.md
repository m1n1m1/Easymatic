---
title: FAQ
description: Short answers, with links to the long ones.
sidebar:
  order: 2
---

## Does it need root?

No. Nothing in Easymatic requires root, and nothing behaves differently with it.

## Does it need an internet connection?

Only the nodes that are *about* the internet — HTTP, mail, AI, and a Home Assistant or
MQTT hub that is not on your network. Triggers, device settings, sensors, files, photos,
calendar and the whole graph engine work offline.

## Does my data leave the phone?

Only where a node sends it. AI prompts go to whichever provider you configured — or
nowhere at all, if you point it at a server you run. Everything else stays local:
workflows, run logs, variables and every credential are files in the app's private
storage, and secrets are sealed by the device's keystore.

## Can two macros share a value?

Yes — declare a **global variable**. See [Variables](/docs/concepts/variables/).

## Can a macro run another macro?

Not directly as a call. What exists is **Enable Macro** / **Disable Macro**, the *macro
finished* trigger, and shared variables. For a genuine call-and-run, a *Called by Another
App* trigger works from any app including a shell — see
[the process API](/docs/extend/process-api/).

## Why is there no "condition" you can attach to a node?

Because it read as hidden control flow: nothing on the card said whether a condition was
incoming or outgoing, and it duplicated what **If** already shows visibly. "Run this only
when X" is an *If* upstream, including for triggers. See
[Values and transforms](/docs/concepts/values-and-transforms/#there-is-no-way-to-attach-a-condition-to-a-node).

## Why does a loop have no wire back to the top?

Because that wire would be an execution cycle, which the validator reports as an error.
The executor is what goes round again, not the graph. See
[Control flow](/docs/concepts/control-flow/#loops).

## Why can I not type a variable name / a place / a light?

Because a mistyped identifier does not fail loudly — it names something else, or nothing,
and the node just looks broken. Identifiers come from choosers. Four things people
genuinely type — a phone number, a time of day, a Wi-Fi network and a person's name — get
an editable field *with* a chooser beside it.

## Why is there a Convert node in my wire?

You dropped a value into a port of a different type, and a conversion existed, so
Easymatic inserted one **visibly** rather than converting behind your back. Retype it or
delete it. See [Autocast](/docs/concepts/types/#autocast).

## Why does my macro still run when it has a problem?

Because a problem quarantines **the smallest thing that is actually broken**. One bad wire
blocks the node that reads it; a warning blocks nothing at all. An all-or-nothing gate
would make one bad wire indistinguishable from a macro that had never been armed. See
[How a macro runs](/docs/concepts/running/).

## Why do the light nodes say "light" rather than "Hue"?

Because they are not Hue-specific: Home Assistant's lights, areas and scenes drive the
same three nodes. Palette search still finds them by "hue", because the app names live in
the node descriptions.

## Which messengers are supported?

All of them. The integration runs over notifications, which every messenger posts, so one
this app has never heard of works on the day it is installed. There is no package list.
See [Messengers](/docs/integrations/messengers/).

## Can a macro send a WhatsApp message on its own?

It can **reply** to one silently. It cannot **start** a new conversation silently — no
messenger offers a path for that, so *Send Message* opens the app with the text filled in
and somebody taps Send. The node's output says *opened* rather than *sent* for that
reason.

## Can it read a QR code?

Not out of the box. There is no system-level scan action on Android, so anything that
does it depends on a specific scanning app being installed.

## Can I use my own AI server?

Yes. Choose the **Self-hosted / OpenAI-compatible** provider and give it your address.
vLLM, Ollama, LM Studio and llama.cpp all work, and the editor has presets for their
default ports. Nothing leaves your network.

## Is my API key safe?

It is sealed by the device keystore and never read back into the UI. If the phone cannot
store it securely the screen says so rather than pretending. A key used by a macro that
runs constantly is worth keeping separate from your other one — quota is per key.

## What happens to my macros when I update the app?

They load unchanged. Files are read leniently, so a field added by a newer build fills in
from its default. The schema version moves very rarely, and when it does, older files are
discarded rather than migrated — so back up anything irreplaceable.

## Where are macros stored, and can I back them up?

Each workflow is a JSON file in the app's private storage. There is no export screen yet;
`adb backup` or a rooted copy of the app's data directory is the current answer.

## Can I translate it?

The app ships eight locales, and node text is generated from the declarations into
standard Android string resources. Contributions go through the
[repository](https://github.com/m1n1m1/Easymatic).
