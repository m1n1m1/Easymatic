---
title: Backup and restore
description: Saving every macro and library to one file, bringing it back, and what Android carries on its own.
sidebar:
  order: 4
---

The question this answers is: **if this phone is lost, what do I get back?**

## Two ways, one set

**Setup → Backup and restore** writes everything to a single encrypted file and reads
one back. **Android's own backup** carries exactly the same set, so a new phone set up
from the old one, or restored from your Google account, brings your macros along with no
step of yours.

The set is: every macro, your global variables and their values, and every library —
places, NFC tags, mail accounts, smart home hubs, AI connections, and which plugins and
apps you have allowed.

Not in it: the run log, files your macros wrote into Easymatic's own folder, and
unfinished recordings.

## The backup password

Every backup file is encrypted with a password you choose when you make it. Nothing in
the file can be read without it: not the macros, not the variable values, not the
places, and not the credentials.

- **Backing up** asks for the password first, then where to save the file. At least
  eight characters.
- **Restoring** asks for the file's password in the confirmation, and checks it before
  anything is touched. A wrong password changes nothing.
- There is no way to recover a forgotten password. The file cannot be read without it,
  which is the whole point, so choose one you will keep.

## Passwords and keys

Mail passwords, hub keys and AI keys are stored sealed, with a key that never leaves the
phone that made it. On another phone, or after a reinstall, that key is gone. So every
backup file carries them a second time, under the backup password, and the same password
puts every credential back on any phone.

Android's own backup carries the credentials under the password you last used. After
an Android restore the app asks for it once. If you rely on Android's backup alone and
have never made a file, set the password from the Backup screen.

If you typed a credential by hand before unlocking, unlocking keeps what you typed and
fills in only what was still missing.

## Folder access does not travel

A folder your macros may reach is granted on the phone, not stored in a file. After a
restore, open **Setup → Folder access** and grant it again. Nothing in a macro has to be
edited: a macro names a path, and the grant is looked up when it runs.

## What restoring does

Restoring from a file **replaces** your libraries and settings with the file's and
**adds** its macros.

- A macro whose id is not on the phone comes back under its own id, armed if it was armed.
- A macro that already exists on the phone is kept. The file's version is added beside it
  as a copy, switched off, so nothing fires twice.
- A copy keeps its variable values, filed under its new id.

The dialog before a restore says how many macros the file holds, when it was saved and
which version wrote it. Nothing is touched until you confirm, and a file that cannot be
read, or was written by a newer Easymatic, is refused with the phone as it was.

## Note

The file is not a zip, even though a zip is inside it: one line saying what it holds and
how to check the password, then everything else encrypted. A file that was cut short or
changed anywhere is refused before a byte of it is unpacked.
