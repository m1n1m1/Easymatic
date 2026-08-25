---
title: NFC tags
description: The tag library, what a tap can and cannot reach, and writing tags.
sidebar:
  order: 9
---

A tag works **straight out of the packet** — blank, read-only, or already written for
something else. The trigger matches on the tag's factory-burned hardware id, so there is
nothing to write and nothing to configure on the tag itself.

## Two platform limits

Both belong on this page rather than buried anywhere, because both look like bugs:

- **There is no background tag scanning at all.** A tag is dispatched to an Activity or
  to nobody, so **a tap cannot reach a phone whose screen is off or locked.**
- **An app cannot switch the NFC radio on.** `NfcAdapter.enable()` is system-only, which
  is why there is no *Toggle NFC* action.

Peer-to-peer (Android Beam) was removed in Android 14, so there is no phone-to-phone
half to build either.

## The trigger

**NFC Tag** with no tag chosen matches **any tag**. That is a deliberate answer, not a
job half-done, and the field says "Any tag" rather than "None selected".

Choose a tag and it matches that one.

## The library

**Setup → NFC tags** maps a hardware id to a name. It is **cosmetic**: the uid *is* the
key, so re-scanning a sticker updates its row rather than adding a second, and deleting
a row costs a node its tag *name* and nothing else — the tap still matches.

That is why there is no Problems entry for a tag that has been deleted, unlike a deleted
variable or macro. A rename reaches an already-armed macro without a re-arm.

The tag chooser **produces its options rather than listing them**: a tag you have never
scanned is in no list, so opening it starts a capture — hold a tag to the phone and it
appears, with your saved tags above.

## Writing tags

The Tags screen can write a tag, which exists to make it useful **to other apps** and to
give the trigger's `text` output something to carry. It is deliberately not on a node
and not inside the capture chooser — two reader-mode registrations would fight.

## The value node

**NFC enabled** answers whether the radio is on. It declares **no permission**, on
purpose: a node whose whole job is to answer *"is the radio on?"* must never be badged
in the Problems panel for the radio being off. That is the node working.

The prerequisite belongs on the *trigger*, which genuinely cannot fire without it.

There is no *"when NFC is switched on"* trigger, because the platform publishes no
broadcast worth arming a macro on.
