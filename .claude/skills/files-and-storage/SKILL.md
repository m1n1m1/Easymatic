---
name: files-and-storage
description: Read before touching the file nodes or storage — the six `action.file_*` nodes, the `Files` facade, `RoutingFiles`/`AppFileStore`/`SafFileStore`/`SafGrants`/`StorageVolumes`, `FilePath`, `FileGlob`, the `@FilePath` config annotation and the Folder access screen. Covers why there is no storage-area concept, why a node never stores a grant, why there is no value node, and the SAF behaviours that fail silently.
---

# Files and storage

**Easymatic can read and write files, and it declares no storage permission to do it.** That is not a loophole — it is the same road `action.play_sound` has always taken: the editor's chooser hands back a URI the app has been *granted*, `takePersistableUriPermission` makes that grant outlive the task and a reboot, and the engine opens it days later from the foreground service. The rule generalises: **choosing needs an Activity; using does not.** Everything below follows from taking that one-file arrangement and widening it to a folder.

## The limit that cannot be designed away

Android will not open a path this app has not been granted. `MANAGE_EXTERNAL_STORAGE` is the only API that lifts it, and Play permits that only for file managers, backup, antivirus, document management, on-device search, encryption and device migration — not automation. `READ_/WRITE_EXTERNAL_STORAGE` are dead at `targetSdk 36` and `requestLegacyExternalStorage` stopped being an escape in Android 11.

Three consequences belong in user-facing copy, not only in comments:

- Android 11+ **refuses a tree grant** on the root of internal storage, on the `Download` directory itself, and on memory-card roots. Sub-folders of all three are fine, so `Download/Easymatic` works where `Download` does not. The chooser simply declines, which reads as the app being broken — the Folder access screen says so first, and `SafFileStore.noGrant` gives `Download` its own sentence because the ordinary "add the folder" advice is an instruction that cannot be carried out there.
- **`Download` therefore has an escape hatch, and it is the only reason single-file grants exist.** `ACTION_OPEN_DOCUMENT` hands over one file at a time with no such restriction, so `SafGrants.files` keeps those grants and `SafFileStore.uriOf` checks them *before* looking for a covering folder. They are read-only by nature rather than by policy — the single-document chooser conveys read access and nothing asks it for more — so they serve `action.file_read` and `action.file_info` and never a write or a delete.
- **MediaStore is not the answer for `Download`, which is worth knowing before somebody proposes it.** On Android 13+ there is no runtime permission that reads *non-media* files another app put there: `READ_EXTERNAL_STORAGE` no longer applies and the `READ_MEDIA_*` split covers images, video and audio only. A PDF or a CSV in `Download` is reachable through SAF or not at all. MediaStore would still serve files this app created itself, which is a much smaller feature than it sounds.
- `Android/data`, `Android/obb` and every other app's private storage are unreachable by any route.
- Persisted grants are **capped** per app, and going over does not fail: the platform evicts the **oldest** grant silently, which on a real phone is very likely `action.play_sound`'s sound URI.

## There is no storage-area concept, and that is the design

An early cut had `StorageArea { APP_FOLDER, SAVED_FOLDER }` plus a folder picker beside every path field. It was wrong, and the reason generalises past this feature: **the split was Android's problem modelled as the user's vocabulary.** A person says where a file is; which mechanism opens it is plumbing.

So every file node's location is one property:

```kotlin
@Label("File") @FilePath @Wired val path: String = "",
```

`RoutingFiles` answers one question per call — *which handle opens this path?* A path with no leading `/` is the app's own storage and needs no grant; anything else goes to whichever granted folder covers it; nothing covering it is a failure naming the path and saying where to fix it.

Removing the enum made the design strictly better in a way worth remembering: because nothing in a workflow references a grant, **re-granting a folder repairs every macro on the phone with nothing edited.** A re-grant produces a *different* tree URI for the same folder, so had nodes stored a folder id or a URI, one re-grant would have orphaned the lot.

## `@FilePath` is the fifth editable-with-a-chooser field

After `@PhoneNumber`, `@TimeOfDay`, `@WifiNetwork` and `@ContactName` — and it earns the shape more sharply than any of them, because two of the three arguments are absolute rather than usual:

- **The answer set cannot be closed.** A chooser offers files that exist *now*, and the point of a write is a file that does not. "Read the file the last run wrote" names a file absent at config time.
- **It must be `@Wired`.** Every file macro worth writing builds its path with `transform.text`, and a read-only picker can never be wired.
- A path is **legible**, so a wrong one reads as wrong — the half `@Picker` exists for and the half this does not need.

The chooser does **two jobs**: it takes the grant *and* fills the path in. Two entries, because Android has two and they are not interchangeable — *Choose a folder* (`OPEN_DOCUMENT_TREE`) grants everything inside and is the one to reach for; *Choose a file* (`OPEN_DOCUMENT`) grants exactly one and cannot name a file that does not exist yet.

Adding it cost one member in `ConfigAnnotations`, one `ConfigFieldType`, one branch each in `NodeSchema.stringFormType`/`checkWidgetAnnotations`/`widgetFlags`, and the compile-forced branch in `ConfigFieldEditor`.

## There is no value node, and the argument is three-part

`value.file_exists` looks obvious and is wrong. The weak form of the objection ("a granted folder may be cloud-backed") does not cover an app-storage read, which genuinely is a cheap syscall — so use all three:

1. **Nothing pushes.** `value.ha_state` and `value.mqtt_topic` are legal because a socket keeps a map warm; a filesystem answers only when asked. This is `value.light_state`'s side of the line.
2. **The answer depends entirely on config** — which is the property already excluding `value.variable` and `value.ha_state` from `sourceOptions()`, since a `val:` read is performed with no config. It could never be an `action.if` source, which is most of why a value node is worth having.
3. **It could not answer `null` honestly.** "There is no file", "no grant covers this" and "the provider failed" are three sentences somebody needs. `action.file_info` keeps them apart with `exists` beside `error`.

## Confinement is two gates, and both are needed

`FilePath.parse` (`domain/model/`, pure, JVM-tested) refuses any `..` **before normalisation** — so `a/../../b` is refused rather than quietly resolved — plus `.` and empty segments, control characters and backslashes (a wired Windows path is a realistic input). This is a security boundary rather than a formatting check, because the property behind it is `@Wired` and an HTTP response, a script result or a `trigger.api` input can carry it.

`AppFileStore` then re-checks the resolved canonical path, because `FilePath` is pure and **cannot see a symlink**. Its root is `{filesDir}/macrofiles/` and never `filesDir` itself, which holds `workflows/`, the run log and every credential library: a confinement bug rooted there would not be a file bug, it would delete somebody's macros.

## The seam is at the operations, not under them

`FileStore` is an interface over the six operations, implemented twice — `SmartHomeVendor`'s lesson rather than a stylistic preference. The two backends need **different numbers of calls**, not differently-shaped ones: creating parent folders is one `mkdirs()` versus a query-then-create per missing segment; appending is a mode flag versus a read-modify-write; a rename is one syscall versus one IPC or a whole byte copy. A wrapper over `java.io.File` and `DocumentFile` would have forced one into the other's request shape.

`RoutingFiles` is "the only class that knows more than one backend exists". Grants are resolved **on every call** rather than cached, because somebody adds a folder on one screen and runs the macro on the next.

## What fails silently

Every one of these is wrong-but-plausible, and none reports itself:

1. **`openOutputStream(uri, "w")` does not truncate.** Writing two bytes over a kilobyte leaves 1 022 stale ones, and the result still parses as *something*. It is `"wt"` everywhere.
2. **`createDocument` never overwrites** — a collision becomes `notes (1).txt` — and a recognised mime type may **append its extension**, so `text/plain` turns `data.csv` into `data.csv.txt`. The cure is general rather than a table: the display name is read back off the returned URI and carried on `FileResultItem.name`. The mime fallback is `application/octet-stream`, which providers leave alone.
3. **`openOutputStream(uri, "wa")` is not honoured by every provider**; one that ignores the `a` truncates, turning "add a line to the log" into "replace the log" on some phones only. Appending is read-modify-write.
4. **`DocumentFile.listFiles()` is 1 + N IPC** — `getName()`, `getLength()` and `isDirectory()` are each their own binder call — so 500 files is ~1 500 round trips, potentially network ones. Listing uses `buildChildDocumentsUriUsingTree` plus **one** query for every column.
5. **`getDocumentId` throws on a tree URI** (it is `getTreeDocumentId` for the root), and **`buildChildDocumentsUri` without `UsingTree` throws** under a tree grant. Both read as permission problems.
6. **`COLUMN_SIZE` and `COLUMN_LAST_MODIFIED` are nullable** and cloud providers routinely omit them. Carried as **-1, never 0** — zero is a lie a macro acts on, reading an unmeasured file as empty.
7. **`deleteDocument` on a directory is recursive** where `File.delete()` refuses a non-empty one. Same node, same path, two opposite outcomes, one unrecoverable — so `action.file_delete` **refuses folders** on both backends.
8. **A cloud restore brings back the macros and no grants at all.** `allowBackup` is true, so a restored phone looks correctly set up and every file node fails. There is no stored library to go stale precisely because `persistedUriPermissions` *is* the list.
9. **A live grant on a deleted folder.** Health asks two questions: the permission list says the grant is held and says nothing about the folder. The second is a query against the folder itself — an IPC, possibly a network call — so it runs on the Folder access screen and **never** on an execution path or a form render.
10. **A grant that cannot be resolved to a path was silently dropped**, which made "Choose a file" decorative. `SafGrants.folders` ran every persisted permission through `StorageVolumes.pathOf`, which calls `getTreeDocumentId` — that *throws* on a single-document URI, was swallowed by `runCatching`, and the grant vanished from the list. So picking a file took a real grant that the router could then never use, and reading it reported "not been given access" to a file the user had just chosen. Tree grants and document grants are now read separately, by `DocumentsContract.isTreeUri`.
11. **The downloads provider does not spell ids like storage does.** A file picked out of the Downloads shortcut comes from `com.android.providers.downloads.documents`, whose ids are `raw:/storage/emulated/0/Download/x.pdf` or `msf:1000000123` — not `volume:relative/path`. The `raw:` form is handled (`rawDocumentPath`, pure and JVM-tested); `msf:` is a row id and genuinely unresolvable, so it is refused and the chooser says the file cannot be named rather than storing a dead grant. Browsing to the same file through the *internal storage* root instead gives a `primary:` id that resolves normally, which is why one file works and another does not for no visible reason.
12. **`removePrefix` cannot say no**, and this one actually shipped. `SafFileStore` derived a tree-relative path with `parentPath.removePrefix(rootPath)`, which returns the string **unchanged** on no match — so a path outside the granted folder came back absolute, and `folderOf` split it into segments and *created* each one, reproducing `storage/emulated/0/Documents/…` as real folders inside the granted folder. It threw nothing and logged nothing: a copy built a duplicate of the whole tree underneath itself. It is now `relativeTo`, top-level and `internal` so it is JVM-testable, answering **null** for anything not under the root. The general form: a strip-the-prefix helper that cannot fail will eventually be handed something without the prefix, and here the code on the far side of it creates directories.
13. **`StorageVolumes` mapping a volume wrongly** makes a path resolve to no grant, and the node then says the folder has not been granted while the grant sits in the list looking correct. That is a worse sentence than a plain failure, which is why it is its own class.
14. **Case-insensitive volumes**: FAT and exFAT fold case where ext4 does not, so `Docs` and `docs` may be one folder or two. Names are matched exactly.

## Text, binary, and the bound

Read is **text only** — the graph has no bytes type and one is not worth adding for one node. Decoding is **total**, so reading a JPEG succeeds and yields U+FFFD nonsense; that is stated in `action.file_read`'s KDoc rather than guarded by a sniffer.

The bound is applied **while the stream is read**, not by trimming afterwards: this runs inside `MacroEngineService` beside every armed macro, so a 200 MB read is an OOM that takes the lot down. A truncated read still reaches the port with a WARN, on `action.ai_prompt`'s rule.

**Copy, move, delete, list and info work on any file, binary included**, because content never becomes an `Item`. "When a photo lands, move it to Archive" is expressible; "read a photo and post it" is not. The transfer is a stream copy rather than `DocumentsContract.moveDocument`, which needs a flag providers may not set and works within one provider only — so it could serve some transfers and not the interesting ones. A move deletes only after the copy has landed.

## Deferred, and why

- **A "file appeared" trigger.** No reliable push channel: a `ContentObserver` on a tree is provider-dependent and reports only *something changed*, so the honest version polls and diffs — and a poll over a cloud-backed folder is a network request per tick. `FileObserver` works over the app's own storage, which is the version worth revisiting.
- **"Save the HTTP response to a file."** `SystemServices.httpRequest` buffers the body into a `String`, so the missing piece is a streaming path in that facade, not a node. Today: `action.http` → `action.file_write`, fine for text, wrong for binary.
- **`@Suggested` paths**, recursive delete, recursive listing, and MediaStore `Downloads` as a third backend.
