---
name: nfc-tags
description: Read before touching NFC — `trigger.nfc`, `value.nfc`, the tag library (NfcTag/NfcTagRepository/PickerKind.NFC_TAG), the reader-mode capture chooser, the write flow, and `TriggerBus.emitOrHoldBroadcast`. Covers why a tag tap needs an Activity and a new hold mechanism, why the tech filter names Ndef rather than NfcA, and why the value node declares no prerequisite when the trigger does.
---

# NFC tags

**Tags are the whole of NFC on Android.** Peer-to-peer (Android Beam) was deprecated in API 29 and **removed in Android 14**, so there is no phone-to-phone transfer half to build. Host card emulation is alive but only ever fires for a reader configured with *our* AID, which a payment terminal never is. Two platform limits shape everything else and both belong in user-facing copy rather than only in comments: **there is no background tag scanning at all** — a tag is dispatched to an Activity or to nobody, so a tap cannot reach a phone whose screen is off or locked — and **an app cannot switch the NFC radio on** (`NfcAdapter.enable()` is system-only), which is why there is no `action.nfc`.

## Identity is the hardware id, and that decides almost everything

`trigger.nfc` matches on the tag's factory-burned uid, hex-encoded by `NfcTagId` (`domain/model/`, pure and JVM-tested, the `WifiSsid` of this feature). That choice is why the trigger works with any tag straight out of the packet — blank, read-only, or already written for something else — with nothing to write and nothing to configure on the tag. Blank config means **any tag**, so `PickerFieldChrome` grew a `placeholder` parameter: "None selected" would describe a deliberate answer as a job half-done.

Three consequences follow and each is load-bearing:

- **The tag library is cosmetic.** `NfcTag` (`domain/model/`) is `uid → name`; the uid *is* the key, so re-scanning a sticker updates its row rather than adding a second. Deleting a row costs a node its `NfcScan.tagName` and nothing else — the tap still matches. So there is deliberately **no `GraphValidator` pass** for a dangling tag reference and **no `domain/registry/NfcTags`**, unlike `validateMacroRefs`/`validateVariableRefs` where the reference *is* the behaviour. `TriggerHost.nfcTag(uid)` resolves the name at fire time, the way `geofencePlace(id)` does — so a rename reaches an already-armed macro with no re-arm, which is why `NfcTagsViewModel` does not call `MacroEngineService` at all where `GeofencePlacesViewModel` must.
- **The chooser produces its options rather than listing them.** A tag never scanned is in no list, so `PickerKind.NFC_TAG` opens a capture overlay with the saved tags above it. It is still a read-only `@Picker` and not a `@WifiNetwork`-style editable field: `04A23F1B` is opaque, where an SSID is legible enough to read back and check.
- **Writing changes nothing the trigger reads.** `NfcWriteOverlay` exists to make a tag useful to *other* apps and to give `scan.text` something to carry. It lives only on the Tags screen, never on a node, and never inside the capture overlay a picker opens — two reader-mode registrations would fight.

## The delivery path, and why `TriggerBus` grew a second hold

A tap **is** what launches the process, so almost every tap is a cold start. `emitOrHold` cannot serve it: it parks under `TriggerEvent.triggerNodeId`, and a `NodeId.BROADCAST` event parked there is drained by nothing, because no trigger collects `eventsFor(BROADCAST)`. Addressing each `trigger.nfc` node the way `GeofenceReceiver` does is not available either — geofence node ids arrive in the intent, whereas these would mean a full `WorkflowRepository.load()` per enabled macro on the tap path (`WorkflowSummary` carries no nodes), which is exactly the work `GeofenceReceiver`'s KDoc refuses.

So `emitOrHoldBroadcast` delivers live **and** parks a copy while `starting` is true, with `HeldBroadcast.deliveredTo` keeping "one tap, one run" true across two nodes and across a re-arm. Both halves matter: `emitOrHold`'s branches are exclusive, which is right when "somebody is collecting" answers "is *this node* collecting" — for a broadcast it answers nothing of the kind, because `rearmAll` arms macros one at a time. `MacroEngineService.rearmAll` closes the window in a `finally`; `engineReady` deliberately does **not** empty the queue, because arming only *launches* a collector and the last macro armed has almost certainly not subscribed yet. What is parked expires on `BROADCAST_HOLD_MAX_AGE_MS` instead.

This fixed `trigger.boot` and `trigger.sms` as a side effect — both emitted plainly to `BROADCAST` at the one moment nothing can be subscribed — but only because the *triggers* also moved to `busEventsFor(node.id)`. A receiver switching to `emitOrHoldBroadcast` alone achieves nothing; the drain hook is on `eventsFor`.

`NfcTagActivity` (`data/trigger/`) wears `RunTriggerActivity`'s disguise for its reasons, and does all its work **synchronously in `onCreate` before `finish()`** — being the foreground activity for that instant is what makes the foreground-service start legal on Android 12+. It overrides `onNewIntent` because the dispatch intent is `SINGLE_TOP` and `finish()` is not synchronous, so a second tap during teardown lands there; without it `NfcTapFilter` would make the loss look like correct de-duplication.

## The tech filter is the part that annoys people if you get it wrong

Matching is AND within a `<tech-list>`, OR between lists, and there is **no negation** — so `IsoDep` cannot be excluded. `res/xml/nfc_tech_filter.xml` therefore names techs payment cards do not have (`Ndef`, `NdefFormatable`, `MifareUltralight`, `NfcV`) rather than the obvious `NfcA`, which would put Ottomatic in the tag-handling chooser every time somebody taps their wallet. `MifareClassic` is omitted for the same reason: it would catch Classic stickers and a great many transit cards with them. `TECH_DISCOVERED` rather than `TAG_DISCOVERED`, which is only consulted when nothing else handled the tag.

Writing a **Link** re-enters that dispatch order at the NDEF stage, ahead of us, so a browser claims the tag and the trigger stops firing. The write overlay says so before it writes. A **Text** record is `TNF_WELL_KNOWN`/`RTD_TEXT` and maps to no URI or MIME, so it falls through to us. An Android Application Record was considered and rejected: with no NDEF filter of our own the system would launch `MainActivity` on every tap.

## Reader mode

`NfcReader` (`data/nfc/`) holds every platform detail. `enableReaderMode` **suppresses the manifest dispatch entirely** while it is active, which is what makes scanning a tag during setup not also fire the macro watching it. Three things there are easy to get wrong:

- `FLAG_READER_SKIP_NDEF_CHECK` must **not** be set — with it `Ndef.get(tag)` returns null and both the content preview and the whole write flow stop working.
- `onTagDiscovered` runs on a **binder thread**, so it publishes through the ViewModel; `Ndef.getCachedNdefMessage` is the discovery-time read, needing no `connect()` and no I/O.
- `disableReaderMode` in `onDispose` is not optional: the registration outlives the composable, and skipping it leaves the app swallowing every tag tap for the rest of the process.

The overlay reaches the Activity through `Context.findActivity()` (`feature/`), the first of its kind here. `LocalActivity` needs activity-compose 1.10 and this build pins 1.8 — and a bare `LocalContext.current as? Activity` would be **null** anyway, because `EditorOverlay` is a Compose `Dialog` whose content is hosted in a `ContextThemeWrapper`.

NDEF Text decoding masks the status byte with **`0x3F`, not `0x7F`**: bit 6 is reserved, and reading it yields a language length that slices the text in the wrong place. URIs go through `NdefRecord.toUri()` rather than a hand-rolled prefix table. `NfcTagId.isUnstable` warns at capture time about ids that differ on every tap — a 4-byte id beginning `0x08` is a random id by ISO/IEC 14443-3, and `NfcB` regenerates its identifier by design.

## The prerequisite, and the value node that deliberately lacks one

`PrerequisiteType.NFC` is keyed by type with a null manifest, like `OVERLAY`: `android.permission.NFC` is install-time and always held, so a *permission* check would never find anything wanting — what decides whether the trigger fires is a system toggle with its own Settings page, which is the shape this enum already models. The manifest **must** carry `<uses-feature android:name="android.hardware.nfc" android:required="false" />`, because declaring the permission otherwise implies `required="true"` and makes the app uninstallable on every phone without a chip.

`isPrerequisiteSatisfied` answers **satisfied** when there is no NFC hardware, for `existsOnThisApi`'s reason: the Permissions screen asks "what does the app need and what has it got", and a row that can never go green is worse than no row. The node's question is different, so `NfcTagTrigger.activate` reports it precisely — along with the per-app tag-intent switch (`NfcReader.tagIntentsAllowed`, API 35+), which nothing else in the app knows about and which silently kills the trigger.

`value.nfc` declares **no** prerequisite, and this is the opposite call from `value.wifi_network`'s: a node whose job is to answer *"is the radio on?"* must not be badged for the radio being off — that is the node working. It is also the one value node with no trigger counterpart, since the platform publishes no adapter-state broadcast worth arming on; the pairing rule in `CLAUDE.md` runs trigger → value, not back.
