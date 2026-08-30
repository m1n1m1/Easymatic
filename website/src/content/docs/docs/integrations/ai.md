---
title: AI
description: Connections, model profiles, and what the model is allowed to do.
sidebar:
  order: 1
---

Two nodes use AI directly — **Ask AI** and **Describe a picture** — and the editor's
graph assistant uses the same setup. Everything is configured in **Setup → AI**.

There are **two layers**, and the split is the useful part:

- a **connection** is an *account* — which provider, and your key;
- a **model profile** is *one saved way of asking* — a model, a persona, a tool
  allowance and a reply length.

A node points at a **profile**, not at a connection. That is what lets you answer
"this is my household assistant: this model, these house rules, these powers" once and
reuse it four times.

## Providers

| Provider | Key from | Notes |
| --- | --- | --- |
| Google Gemini | Google AI Studio | Generous free tier — but it is your quota |
| Anthropic Claude | the Anthropic Console | Billed to your own credit |
| OpenAI (ChatGPT) | the OpenAI platform | Billed to your own account |
| OpenRouter | OpenRouter | Price depends on the model you pick; you must name model ids |
| Self-hosted / OpenAI-compatible | your own server | vLLM, Ollama, LM Studio, llama.cpp. Nothing leaves your network |

There is no separate row for Ollama, LM Studio, vLLM or Groq: every one of them serves
OpenAI's `/v1/chat/completions`, so each is the **Self-hosted** provider with a
different address. The editor offers ready-made presets for the four common ones.

## Adding a connection

1. **Setup → AI → +**, and choose a provider.
2. The screen shows that provider's own steps for minting a key, in the order you
   actually do them, with a button that opens the right console page. You are not
   expected to transcribe a URL.
3. Paste the key. There is a paste button beside the field.
4. Name the connection — "Personal", "Work key". The name is what the pickers show.

Your key is **sealed on the device** and never read back into the UI. If the phone
cannot store it securely the screen says so rather than pretending.

Two connections on one provider is a perfectly real setup, because **quota is per
key**: the macro that fires every five minutes and the one that summarises your mail
can be kept from starving each other.

### Self-hosted

Set the **base URL** including the scheme and the port — `http://192.168.1.10:11434/v1`.
Unlike other URL fields in the app this one **refuses a scheme-less address rather than
guessing**, because both `http` and `https` are ordinary for a model server.

Paste an API key if your server wants one; many local servers accept anything.

## Model profiles

A connection with no profile is complete but has nothing to point a node at. Add one
per way of asking:

- **Name** — your own word for it. "Household", "Summarise".
- **Model id** — leave blank to use the provider's own published model for the chosen
  speed. **OpenRouter and self-hosted servers must name one**: OpenRouter serves
  hundreds of models where no three are the obvious tiers, and a self-hosted server
  serves exactly one model whose name only you know. Both offer a *Load models* button.
- **Speed** — Fast, Balanced or Thorough. This is a real setting, not a label: it
  drives Gemini's thinking level, OpenAI's reasoning effort, and whether Anthropic's
  fast tier is asked to think at all.
- **Instruction** — a standing persona. It is **combined with** a node's own
  instruction, profile first, rather than replacing it: the two answer different
  questions, and overriding would mean any node setting a task instruction silently
  threw the persona away.
- **Longest reply** — the default for anything asking through this profile that does
  not state a limit of its own. An *Ask AI* node's own "Longest reply" field still wins
  where it is set. The default is generous because the caller this exists for is the
  graph assistant, whose turns spend their budget on tool calls before a word of the
  answer is written.

## What the model is allowed to do

A profile carries a **tool allowance**: which of Easymatic's own nodes the model may
run on your behalf.

The screen offers the **whole candidate set**, ticked or not, so "allow everything" and
"allow nothing" are one gesture each. A row that is ticked but still needs a visit is
badged: a tool whose target is an identifier nothing can enumerate — a specific place,
a specific macro — would otherwise run against a default that names nothing.

Where the answer set *can* be enumerated, leaving it open is a real answer: the model
is handed the list to choose from.

A node can also override its profile's allowance for its own call, and a row that has
departed from the profile is marked.

## Where prompts go

- Gemini, Anthropic and OpenAI answer from their own servers.
- OpenRouter forwards to whichever provider serves the model you chose.
- A self-hosted server is the only one where prompts go nowhere else.

An *Ask AI* node therefore takes a moment. It is an action, not a value, for exactly
that reason.

## Costs

Prompts are billed to *your* account, so a macro that asks the AI every minute costs
money — or, on a free tier, uses up your quota. Every key can be revoked from the same
page it was minted on.
