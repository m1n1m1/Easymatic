---
name: images
description: Read before touching the picture nodes — `trigger.image_saved`, `value.latest_image`, the six `action.image_*` nodes, the three screenshot nodes (`action.screenshot`, `trigger.screenshot`, `value.latest_screenshot`), the `Images` facade, `MediaImages`/`MediaStoreQueries`/`MediaWrites`/`MediaConsents`/`ImageEditor`/`ImageExif`/`ImageWatchers`/`ImageDiff`/`ImageSeenStore`/`Screenshots`/`ScreenCapture`, `ImageRef` and `MediaConsentActivity`. Covers why MediaStore is right here and wrong for files, the four-rung write-consent ladder, the pending-row race that silently loses camera photos, the bitmap memory ceiling, why screen capture rides the accessibility service rather than MediaProjection, and the MediaStore behaviours that fail without saying so.
---

# Images

**MediaStore is the backend here, and the files subsystem was right to refuse it.** Those are the same judgement, not opposite ones. `files-and-storage` rejected MediaStore because on Android 13+ **no runtime permission reads non-media files another app wrote** — a PDF in `Download` is reachable through SAF or not at all. That argument inverts exactly for pictures: `READ_MEDIA_IMAGES` does exist. And MediaStore is not merely *available* here, it is the only thing that can answer "the newest photo", "everything in Screenshots" or "pictures taken last Tuesday" — because a folder grant knows a *directory* and MediaStore knows a **collection**.

## A picture has two addresses, and one field

`ImageRef` (`domain/model/`, pure, JVM-tested) reads either a `content://` row uri or a path. Neither half is sufficient and that is forced, not indulgent:

- a **path** alone cannot address every picture. `MediaStore.MediaColumns.DATA` is deprecated, blank on some phones, and — the part that bites — **not openable by `java.io.File` under scoped storage**, so a path this app emitted could not always be reopened by the node after it;
- a **URI** alone cannot reach the rest of the app. `action.ai_describe` and every `action.file_*` node take a `@FilePath`, so a URI-only family could hand a picture to none of them, and "resize this photo and mail it" would stop at the mail node.

This is **not** a breach of the rule against making somebody choose between two platform mechanisms. That rule is about the *user's vocabulary*: there is one field called "Picture", filled from one chooser or from an upstream port, and nothing on screen ever asks which kind of handle you have. Which branch was taken is `MediaImages`' business — exactly the division `FilePath` draws between a path and the two stores behind it.

`ImageRef` is a security gate as well as a parse, on `FilePath`'s reasoning: the field is `@Wired`, so an HTTP response or a script result can reach it. A `content://` prefix is checked **first**, because such a string is full of `/` and `FilePath` would otherwise read it as a relative path named `content:` that resolves inside app storage and quietly finds nothing. A `file://` URI is **refused** — it names a real file and would reach the filesystem without passing the `..` gate.

## The write-consent ladder, and the Activity it needs

`MediaConsents` owns four rungs, chosen by API level and by **who owns the row**:

| Situation | What happens |
|---|---|
| Easymatic's own row | No consent, on every version. This is why `action.image_edit` is free of the whole mechanism. |
| API ≤ 28 | `WRITE_EXTERNAL_STORAGE`, an ordinary runtime permission. No consent step. |
| API 29 | `RecoverableSecurityException` carries the sender — so that rung runs **after** a failure, not before it. |
| API 30+ | `createWriteRequest` / `createTrashRequest` / `createDeleteRequest` hand over a sender up front. |
| API 31+ with `MANAGE_MEDIA` | Same call, auto-approved with no dialog drawn. |

**The sender needs an Activity and the engine is a service.** `Context.startIntentSender` has no result callback, so a service could never tell approval from refusal — and those must be reported differently. Hence `MediaConsentActivity`, a translucent trampoline on `RunTriggerActivity`'s pattern. Starting *that* from the background is itself blocked from Android 10 unless `SYSTEM_ALERT_WINDOW` is held, which is `ForegroundLaunch`'s fact — so the three mutating nodes declare `PrerequisiteType.OVERLAY`, and `canAsk` is checked **before** the call, because a refused background activity start throws nothing and returns nothing.

Two details in that activity are load-bearing and look like oversights:

- **`noHistory` is deliberately absent** from its manifest entry, where `RunTriggerActivity` sets it. That flag finishes an activity as soon as it stops being visible — which here is exactly when the system dialog appears, so the result would never arrive and every consented write would report a refusal.
- **`onDestroy` completes as a refusal** if nothing else has, so a swipe-away resolves the waiting coroutine instead of hanging the run for ever.

**Three outcomes that must never collapse**, carried all the way to `ImageResultItem`: `changed` (fine), a refusal (`error`, not worth retrying), and `needsConfirmation` (nobody could be asked — worth retrying when the phone is in hand). Folding the last into `error` would make a locked screen indistinguishable from a decision.

## What is uncertain and needs a device

**Whether `MANAGE_MEDIA` lets a plain `resolver.delete()` / `update()` succeed without the IntentSender round-trip.** The documentation only promises that no confirmation dialog is shown. If direct calls do succeed, the trampoline becomes a fallback rather than the main path and background deletes get materially cleaner. Also unverified: whether the system dialog draws over the lock screen (assume not), whether OEM background-start policies (Xiaomi, Oppo) honour the overlay exemption, and whether API 29's `RecoverableSecurityException` reaches us from `update` as well as from `delete`.

## The trigger loses camera photos without the lookback

A `ContentObserver` says only *that* something changed, so `ImageWatchers` debounces and then queries; `ImageDiff` (pure, JVM-tested) decides what is new. Four failures it exists to prevent, all silent:

1. **The first arm replays the camera roll.** A macro armed on a phone with four thousand photos must not run four thousand times, so the bootstrap records where the collection is and fires nothing — `MailSeenStore`'s rule, and the mark is **never cleared on teardown**, because a disarm and a re-arm are indistinguishable from the trigger's `finally`.
2. **A pending row is skipped for ever.** This is the subtle one. From Android 10 a camera app inserts its row *first*, marked pending and invisible to us, then fills in the bytes and publishes. The id was allocated at insert time, so by publication a later id may already have advanced the mark past it — and the picture everybody actually wanted is never reported. `ImageLimits.PENDING_LOOKBACK_SECONDS` over `DATE_ADDED` is what catches it.
3. **The lookback then double-reports.** The fired ring is what makes the lookback safe.
4. **A rebuilt media index.** `MediaStore.getVersion` is the uid-validity analogue: when it changes, row ids are not comparable and the only safe move is to re-baseline — which is a real gap in coverage, so it is a WARN in the node's own console rather than silence.

**`_ID` and not `DATE_ADDED` is the high-water mark**, because `DATE_ADDED` is *seconds*: several photos in one second are indistinguishable and a burst would be reported as one. Both are stored; only `_ID` totally orders.

`ImageLimits.MAX_NEW_PER_SCAN` caps a burst at 20, and **the mark advances past the overflow too** — otherwise the cap turns one large import into an unbounded loop rather than bounding it.

`TriggerSource.MEDIA_STORE` is separate from `MEDIA` (media buttons, card mounts) on `HOME_ASSISTANT`'s reasoning rather than `MESSAGE`'s: those are broadcasts every armed node filters, where this is routed to **one node id**, because every node has its own mark and one photo is new to some armed triggers and not others.

## Bitmap memory is the thing that kills the process

An edit runs inside `MacroEngineService` beside every armed macro, and a decoded bitmap costs four bytes a pixel — a 50-megapixel photo decoded whole is two hundred megabytes. `FileLimits.MAX_READ_BYTES`' reason, stated for pixels. Four rules, none optional:

1. **Two passes** — `inJustDecodeBounds` before a single pixel is allocated, then `ImageScalePlan` (pure, JVM-tested) picks an `inSampleSize`. That value is **always a power of two**, because `BitmapFactory` silently rounds anything else *down* to one, so a plan computing 3 would get a half and every dimension downstream would be wrong by a factor nobody sees.
2. **One intermediate bitmap** — rotate, flip, crop and the residual scale all go into a single `Matrix` and a single `createBitmap`.
3. **Straight into the output stream**, never a `ByteArray`, which would hold the compressed copy beside the bitmap.
4. **A process-wide `Mutex`**, because the caps bound *one* edit and two macros editing at once is double.

`OutOfMemoryError` is caught **by name**: it is an `Error`, so every `runCatching` in this app steps straight past it, and letting it out of the coroutine kills the service.

EXIF orientation is applied **first, always** — a JPEG that "looks rotated" is nearly always an upright bitmap plus a tag, so an edit ignoring it produces a correctly-cropped picture lying on its side.

**`action.image_edit` always writes a new file.** An original can never be destroyed by a macro that ran unattended, and the output is a file this app created, so it needs no permission and no confirmation on any version. "Replace the original" is this node plus `action.image_delete` — two visible steps rather than one hidden one.

## Showing a picture to a model

`action.ai_describe` goes through **`Images.encodeForModel`**, not `Files.readBytes`, and the change is worth knowing because the old wiring failed on essentially every real photo. Two independent faults, both of which look like the feature being broken:

- `Files.readBytes` is bounded by `FileLimits.MAX_READ_BYTES` — **one megabyte** — and *refuses* rather than truncates, because half a JPEG is not a picture. A phone camera produces four. So "ask AI about the photo I just took" reported "that file is too big" for exactly the pictures worth asking about.
- It routes an absolute path through `RoutingFiles`, which sends it to SAF — so a camera photo also needed a **folder grant** on `DCIM`, even though the same picture is a media row readable with the media grant alone.

`encodeForModel` has neither problem: it decodes, downscales to `ImageLimits.MODEL_LONGEST_SIDE` and re-encodes as JPEG, so the size is bounded **by construction rather than by refusal**, and it reads the media row first and falls back to the file router only for a picture the collection does not hold. That fallback is `RoutingFiles.openStream`, handed in by `ServiceLocator` rather than reached for, so there is still one reading of a path.

The downscale is not a workaround. Every vision provider downscales server-side and bills by the pixel, so 1568px is what they themselves recommend — sending a 12-megapixel original costs upload time and tokens to transmit detail the model discards. It is still **announced** in the run log (DEBUG), on `action.ai_prompt`'s truncation rule: somebody asking "why did it miss the small print" needs to know the model saw a smaller picture.

EXIF rotation is applied during that encode and not left to the model, because the tag does not survive the re-encode — a portrait photo would otherwise arrive on its side and be described that way.

## EXIF, and why the framework class is not a substitute

`androidx.exifinterface` is taken because `android.media.ExifInterface` writes **JPEG only** and its `TAG_*` constants arrived piecemeal across API 24–31, so a tag that reads correctly on a modern phone returns null on an older one — silently, with no way for a macro to tell "no camera model" from "this Android cannot read one".

- **Location is redacted by default.** From API 29, holding `ACCESS_MEDIA_LOCATION` is necessary and **not sufficient**: the uri must also go through `MediaStore.setRequireOriginal`. So `ImageDetailsItem` carries three states — `hasLocation` false, plus `locationHidden` for "it has one and we may not read it". Without the third, "taken nowhere" and "not allowed to tell you" are the same answer. `0.0, 0.0` cannot be the sentinel: it is a real place in the Gulf of Guinea.
- **`"rw"`, never `"w"`.** `saveAttributes` rewrites in place and needs a *seekable* descriptor; some providers back `"w"` with a pipe, and the resulting `IOException` reads exactly like a corrupt photo.
- **Writing is narrower than reading.** JPEG, PNG and WebP only; HEIC and raw are read-only. Checked *before* the write and reported by name, because a save that succeeds at every step and changes nothing is the worst outcome available.
- **The collection's columns go stale** after a write — `DATE_TAKEN`, `ORIENTATION` and the coordinates are cached copies of EXIF. `action.image_info` therefore reads the **file** rather than the cursor wherever both could answer.
- One detail per node, **blank removes the tag**. Twelve optional fields is unfixable: blank would have to mean both "leave alone" and "remove". This also gets "strip the location before I share this" for free.

## What fails silently in MediaStore

1. **`RELATIVE_PATH` is stored with a trailing separator.** A query or update without one matches nothing at all while looking perfectly correct.
2. **`openOutputStream(uri, "w")` does not truncate** — it is `"wt"` everywhere, the same trap SAF has.
3. **`insert` renames on a collision** to `photo (1).jpg` and reports success, so the display name is read back off the returned uri. `FileResult.name`'s lesson, identically.
4. **A row is visible the instant it is inserted**, so a new picture is created `IS_PENDING = 1` and published after the bytes land — otherwise a gallery shows a zero-byte thumbnail for as long as the encode takes. A failed encode **abandons** the row rather than leaving an empty photo.
5. **`DATE_TAKEN` is millis and `DATE_ADDED` is seconds.** Getting that wrong puts a photo in 1970 and is invisible until two are compared.
6. **`DATE_TAKEN` is null** for screenshots and downloads, so listings sort on `DATE_ADDED` with `_ID` breaking the tie — sorting on `DATE_TAKEN` piles every screenshot at one end and reads as the listing being broken.
7. **`EXTERNAL_CONTENT_URI` is primary storage alone.** From API 29 `VOLUME_EXTERNAL` covers every mounted volume, and the older constant silently reports half the photos on a phone with a card.
8. **A move must update `RELATIVE_PATH`, not copy-and-delete.** The row keeps its id, its capture date and its place in every other gallery; a copy would make it a *new photo taken today*, with every album and shared link pointing at something that no longer exists.
9. **There is no bin below Android 11**, so `action.image_delete` degrades to a permanent delete — announced with a WARN **before** the delete, because afterwards the picture is gone.
10. **A port name may collide with a config key in the generated string keys.** `port_<typeid>_<port>` carries no direction, so `action.image_info`'s output is `details` rather than `image`.

## The permission rename, and where it is modelled

`READ_MEDIA_IMAGES` at API 33+ and `READ_EXTERNAL_STORAGE` below it are **one capability under two names** — a rename, not a permission that does not exist yet, which is why `AndroidPermissionChecker.existsOnThisApi` cannot answer it. `Permission.onApi(sdkInt)` in `:node-api` is the single place: pure and parameterised so it lives with the constants and is JVM-tested at every boundary. **Three callers, and missing one is the bug it exists to prevent** — a grant requested under one name and checked under the other can never be satisfied.

Nodes declare the *modern* name on every API, which keeps `PermissionRequirement.key` stable as the fleet's floor rises.

`WRITE_EXTERNAL_STORAGE` reports **granted** above API 28, which looks wrong and is honest: there is no permission for changing another app's media there, so a denied row would badge every write node for a switch that does not exist on the phone.

`MANAGE_MEDIA` is a new `PrerequisiteType` and is declared by **no node** — it lives in `PermissionCatalogue.appLevel`. Every image node works without it by asking, so declaring it would put a permanent amber badge on a working node, which is what `value.nfc` and `usesContacts` both exist to avoid. What it buys is "stop asking me every time", which is a Permissions-screen proposition.

`READ_MEDIA_VISUAL_USER_SELECTED` is deliberately not declared: "Select photos" hands over a fixed set chosen once, which is no use to a trigger whose job is noticing a photo that did not exist yet.

## Screenshots are three nodes over one rule

`action.screenshot`, `trigger.screenshot` and `value.latest_screenshot` all resolve "is this a screenshot?" through one place — `Screenshots` (`data/images/Screenshots.kt`) — and that sharing is the entire justification for them existing rather than being advice to configure `trigger.image_saved`. **The folder differs between phones**: `Pictures/Screenshots` on AOSP and most OEMs, `DCIM/Screenshots` on others. `ImageSavedTrigger`'s KDoc rejects a "Camera / Screenshots / Anywhere" *enum* because an enum invents vocabulary the platform lacks — which is right, and is not an argument against a node whose whole subject is screenshots. `isScreenshotFolder` matches any path **segment** starting with `screenshot`, and reads the folder and **never the file name**: a `Screenshot_….png` sitting in `Download` arrived through a messenger and was never taken here.

**Capture runs on the accessibility service, not MediaProjection.** `AccessibilityService.takeScreenshot` (API 30+) is the only route to the phone's own screen with no per-run consent dialog. MediaProjection needs a second foreground service — the engine's `specialUse` type cannot carry `mediaProjection` — plus a consent Activity started from the background, which `AndroidSystemServices.canStartActivity` documents as blocked for exactly that kind of service. `performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)` answers with a boolean and no picture, so a node on it could not say what it produced. The cost, paid knowingly: `accessibility_service_config.xml` now carries `android:canTakeScreenshot="true"`, so the service's old "it cannot see screen content" contract is narrower than it was — it never *watches*, and captures only while an `action.screenshot` node runs. `canRetrieveWindowContent` stays `false`; a frame is not a view tree. The user-facing description strings say so, in all eight locales, because Android lists the capability on the enable screen.

`ScreenCapture` (`data/accessibility/`) reaches the service through `EasymaticAccessibilityService.instance`, a `@Volatile` handle set in `onServiceConnected` and cleared in **both** `onUnbind` and `onDestroy` — a service can be unbound and rebound without being destroyed. It returns a **software** bitmap and closes the `HardwareBuffer` in a `finally`, and it is serialised process-wide because the platform refuses a second screenshot within about a second (`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`).

Three consequences worth keeping straight:

- **`action.screenshot` declares accessibility and nothing else** — no `READ_MEDIA_IMAGES`. It writes a row this app creates, which needs no media grant on any version, and the folder lookup falls back without one. Declaring it would badge a working node.
- **A capture is a row Easymatic owns**, so the whole consent ladder is bypassed — `action.image_edit`'s property, for the same reason.
- **The trigger fires on Easymatic's own captures.** The action saves where the phone saves screenshots, and an owner filter would invent a distinction nobody asked for. Wiring `trigger.screenshot` into `action.screenshot` loops, bounded by the debounce and `MAX_NEW_PER_SCAN`, and visibly so on the canvas.

`ImageWatchSpec.kind` (`ANY` / `SCREENSHOT`) is how the trigger narrows the shared observer, applied in `ImageWatchers.matches` **after** the high-water mark advances — the per-node property that lets one observer serve differently-configured nodes. `ImageEventCodec.TYPE_SCREENSHOT` is the first use of the room `TRIGGER_TYPE` was declared to leave.

## `action.camera_photo` opens the camera itself

The second node that makes a picture out of nothing, and the first that photographs the *world*. `ACTION_IMAGE_CAPTURE` was rejected for exactly the property that makes it easy in an editor: it hands the job to another app in the foreground and needs somebody to press a shutter, so a macro on it does nothing at all while the phone is in a pocket — which is where this node is for. It could also honour neither field the node offers: **lens choice is a hint most camera apps ignore, and a delay is meaningless when a person is taking the picture.** So `CameraCapture` (`data/camera/`) drives camera2 directly, on `ScreenCapture`'s shape — internal object, public sealed `CameraGrab`, process-wide `Mutex`, one sentence per platform error code, `OutOfMemoryError` by name — and is injected into `MediaImages` as a `cameraShot` lambda exactly as the screen grab is, so `data/images/` goes on knowing how a picture is *written* and nothing about where its pixels came from. No `androidx.camera` dependency is added; the torch already talks to `CameraManager`.

**The foreground-service type is the mechanism to know about.** From Android 11 a service's access to the camera follows its declared `foregroundServiceType`, not the app's `CAMERA` grant — a `specialUse` service holds the grant and is still refused the sensor. It cannot simply claim `camera` permanently either: from API 34 `startForeground` **throws** when a named type's permission is not held, so a service that always asked would fail to start — taking every armed macro with it — on every phone whose owner never granted camera access, for one node out of a hundred. Hence `CameraForeground` (`data/service/`, `ForegroundGrant`'s precedent): `MacroEngineService` registers a `Types` callback in `onCreate` and detaches it **by identity** in `onDestroy`, and the type is promoted to `specialUse|camera` for the seconds a capture runs and dropped again. The permission check inside `setCameraType` is not defensive, it is the crash guard. A **failed** promotion is not an error — the capture is attempted anyway and the camera reports Android's own refusal, `ForegroundGrant`'s stance: stop the app refusing before the platform gets a chance to accept.

Four things about the capture that read as details and are decisions:

- **The metering stream is not optional.** A still request fired at a camera powered on a moment ago comes back dark or green, because 3A has no history. So a repeating request runs first — and it must **not** target the JPEG reader, which would encode a full photograph per metering frame. A 320×320 `YUV_420_888` reader whose images are closed on arrival is the headless stand-in for a preview surface.
- **The delay is spent there**, between session configuration and the still request, which is the entire reason it is a field on this node rather than an `action.delay` wired in front: in front it is spent with the camera shut and then photographs with a sensor that has had no time to expose. `ImageLimits.MAX_PHOTO_DELAY_SECONDS` bounds it because a camera is exclusive hardware and the wait is time nothing else on the phone can photograph.
- **`JPEG_ORIENTATION` is the sensor orientation and the display rotation is deliberately not consulted.** The textbook formula adds it, and a headless capture has no display to ask — the engine owns no window, and `WindowManager` reports rotation 0 on a rotation-locked phone however it is being held, so reading it would be wrong half the time rather than right. The honest promise is "upright with respect to the phone's natural orientation". It is a tag, so `ImageExif` and every gallery already honour it.
- **A JPEG `ByteArray` is not a breach of the bitmap ceiling.** `MAX_DECODE_PIXELS` counts *decoded* pixels at four bytes each and nothing here decodes: a 50 MP photo is four megabytes, not two hundred. That is why the node offers no size cap and why the next reader must not "fix" it with an `ImageScalePlan`.

**Opening a camera takes the torch out**, and it is not put back: restoring it would be a hidden side effect and would fight a flash capture. The visible half is the node's own flash field. `trigger.image_saved` fires on these photos, `action.screenshot`'s consequence for its reason. `Permissions.CAMERA`'s KDoc no longer says "declared by no node" — this is the first declarer, though the odd second reason survives: Android refuses `ACTION_IMAGE_CAPTURE` outright when an app declares `CAMERA` without holding it, even though another app takes the photograph.

**None of `CameraCapture` or `CameraForeground` has a JVM-testable seam.** The uncertainties needing a device: whether the type can be promoted at all on API 34+ while backgrounded (the design tolerates failure either way, but unattended capture may only work shortly after foreground use if not), whether `CONTROL_AE_MODE_ON_ALWAYS_FLASH` actually fires the flash or the manual `FLASH_MODE_SINGLE` pair is needed, whether `SETTLE_MS = 700` converges on slower sensors, and whether the largest JPEG plus a YUV metering stream configures everywhere.

## Deferred, and why

- **Video.** The query, the observer, the trash and the consent ladder are all identical; only the pixel operations do not carry over. `data/images/` and `TriggerSource.MEDIA_STORE` are shaped for it, and the typeIds (`trigger.image_saved`, `action.image_*`) stay as they are — a video family is new nodes sharing this plumbing. `action.camera_photo` additionally leaves `action.camera_video` a symmetric home, which is most of why it is named for the camera rather than for the photograph.
- **A thumbnail picker.** `@FilePath` is the right widget today: its chooser shows images and resolves to the same path, and the field must stay typeable and `@Wired` because the interesting picture is usually the one a previous node just found.
- **Batch operations.** `MediaStore.createWriteRequest` takes a list, and one dialog for forty photos is much better than forty — but nothing in the graph currently hands a node a list of pictures to act on at once.
- **Reading pixels into the graph.** There is no bytes type; `Files.readBytes` already serves `action.ai_describe`, which is the only consumer that needs them.
