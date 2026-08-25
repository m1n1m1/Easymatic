---
title: Messengers and notifications
description: Reading and answering WhatsApp, Signal and Telegram — and posting your own notifications.
sidebar:
  order: 6
---

## There is nothing to configure

WhatsApp, Signal and Telegram expose no API to a third-party app on the same phone. So
the whole integration runs over the one channel Android gives every app equally: **the
notification**.

That has one very large consequence in your favour — **a messenger this app has never
heard of works on the day it is installed.** There is no package list to maintain and
deliberately never will be.

What identifies a message is the *shape* of the notification: a messaging-style
notification, or one carrying a reply action. A title and a body is not evidence of a
message, or the trigger would fire on delivery updates.

## The one permission

All the notification-side nodes need **notification access** — the special access
Android grants under *Settings → Notifications → Device & app notifications*, or through
the Grant button on the node's own card.

That single grant covers *Message Received*, *Notification Received*, *Reply to
Message* and *Act on Notification*.

## Reading and answering are asymmetric

**Replying is silent and complete.** A messenger that supports watch or Auto
quick-reply attaches a reply action to its notification; filling that in and firing it
sends a genuine message with no UI. The app cannot tell it apart from a typed one,
because there is nothing to tell apart.

**Starting a new conversation has no silent path on any of them.** The most the platform
offers is the app opened with the text filled in and a person tapping Send. So *Send
Message* is a different node with a different promise, and its output says *opened*
rather than *sent*. A macro that fires it at 3 a.m. leaves a chat window open and
nothing sent.

## The reply handle is a live notification

This is the thing that surprises people: **open the chat and the notification goes, and
with it the only way to answer.**

The practical consequence is to put *Reply to Message* **before** anything that takes
time. A reply after a five-minute Wait is a reply into a chat that has very likely been
read — and Ottomatic reports that case in words rather than as a mysterious failure.

## Two fields that look alike

*Message Received* gives you both:

- **conversation** — what the chat is *called*. Put this in a notification.
- **conversationId** — an opaque handle. This is what *Reply to Message* takes.

They carry the same name across the trigger's output, the reply node's input and its
config field, so the pairing is legible on the canvas. Wiring the readable one in would
look entirely reasonable and reply to nobody.

## Filtering by sender

The sender filter is an **editable field with a chooser beside it**, not a read-only
picker over your address book. The chooser offers your contacts; the answer set is
*every name a messenger might print*, which is wider — a message can arrive from
somebody never saved, from a business account, or under a push name the sender chose
themselves.

So *"when anyone whose name contains Support messages me"* stays reachable. It stores
**the name itself**, not a reference to a contact, which is forced rather than chosen: a
contact reference resolves to a *number*, and a messenger's notification does not
contain one at all.

The honest price is that a later rename in your address book does not follow. The
smaller price, since a `contains` match on the unchanged part usually still holds.

## Numbers get normalised

Address books are full of numbers saved the way they are dialled at home —
`0151 12345678`. A WhatsApp link takes a country code and nothing else, so it would read
`0151` as the country and open a chat with nobody in it, which looks like the contact
not being on WhatsApp.

The phone's own number formatter fixes that, using the **SIM's** country rather than the
network's — otherwise a German phone roaming in France would start reading its owner's
contacts as French numbers, and a macro that worked at home would message strangers on
holiday. What the phone cannot place is passed through unchanged, and the run log says
so.

Only the messenger nodes do this. `tel:` and SMS handle a national number perfectly
well, so *Make Call* and *Send SMS* are left alone.

## Duplicates

A messenger updates **one notification per chat** rather than posting one per message,
and re-posts it whenever it so much as refreshes a badge. So the question is never "have
I seen this notification?" but "have I seen it showing *this message*?" — which is what
Ottomatic tracks.

Group summaries ("3 messages from 2 chats") and ongoing notifications (a call in
progress) are dropped before any of that.

## Notifications Ottomatic posts

**Show Notification** posts one of your own, and it is more than a message box:

- **buttons**, defined as a list, and a **reply field**;
- a **progress bar**, an **ongoing** flag, and an accent colour;
- a **tag**, so **Remove Notification** can take it down later;
- a **timeout**.

It has two execution outputs. The first fires as soon as it is posted; the second fires
when somebody *answers* it — with the button they pressed, its index, and any typed
reply on data ports.

Being ignored pulses nothing at all. The answer arrives as **data** rather than as one
execution port per button, which is what lets the button list be built by the graph.

Posting a notification needs the notification permission on Android 13 and later.
