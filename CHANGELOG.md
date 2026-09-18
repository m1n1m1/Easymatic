# Changelog

All notable changes to Easymatic are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**This file is the only place release notes are written.** The Play Store text, the
GitHub release body, the website's changelog page and the app's own version number are
all derived from it. After editing, regenerate the derived files:

```
.\gradlew.bat :app:testDebugUnitTest --tests "*ChangelogExportTest*" -PregenerateChangelog=true
```

The grammar is strict, because four things parse it. Each release is a
`## [<version>] - <YYYY-MM-DD>` heading, followed by a `code:` line giving the Play
`versionCode`, an optional `Play:` paragraph (the Play Store text, 500 characters at
most), and `###` sections drawn from Added, Changed, Fixed, Removed, Deprecated and
Security. Bullets are single-line and start with `- `. See the "Changelog and releases"
section of CLAUDE.md for why.

## [Unreleased]

## [0.1.2-alpha] - 2026-09-18
code: 102

Play: Choose the language Easymatic is shown in under Setup: any of its eight languages, or follow the phone. The app also no longer asks for notification, location and Do Not Disturb access the moment it opens. A permission is asked for on the node that needs it, and the Permissions screen shows what is granted and what is missing.

### Added
- An App language setting under Setup: choose which of the eight languages Easymatic is shown in, or follow the phone. On Android 13 and later the same choice appears in the phone's own Settings → Apps → Easymatic → Language.

### Changed
- The app no longer asks for notification, location and Do Not Disturb access the moment it opens. A permission is asked for on the node that needs it, and the Permissions screen shows what is granted and what is missing.

## [0.1.1-alpha] - 2026-09-15
code: 101

Play: Backup and restore: every macro and library to one password-encrypted file and back. Search on the macro list, a privacy policy link under Setup, a themed launcher icon and a proper status-bar icon. Screens now animate between each other, the back gesture included. Fixed: the SMS trigger never fired, and AI connections can be tested before they are saved. The app no longer asks to see every installed package.

### Added
- Backup and restore under Setup: every macro and library to a single file and back, and the same set is included in Android's own backup and device-to-device transfer.
- A backup password. Every backup file is encrypted with one, asked for before it is written and before it is restored; nothing in the file can be read without it, and after a restore on another phone that one password puts every mail password, hub key and AI key back.
- Search on the macro list, from a proper app bar on the main screen.
- A link to the privacy policy under Setup.
- A themed (monochrome) launcher icon, and a dedicated status-bar icon for the service notification instead of the launcher icon.

### Changed
- Screens now move as a stack: a new screen slides in over the old one, and the back button and the back gesture play the same animation.
- The app no longer requests to see every installed package. App pickers list the apps that have a launcher entry, so the "Other" section of the notification filter shows fewer apps than before.
- The launcher icon has a larger cutout.

### Fixed
- The SMS trigger never fired: it listened for the broadcast only the default SMS app receives, and now listens for the one every app with the SMS permission gets.
- An AI connection can be tested, and its models listed, before it is saved. A new connection no longer has to be saved, closed and reopened before a model can be chosen or the key checked.

## [0.1.0-alpha] - 2026-08-30
code: 100

Play: The first alpha of Easymatic. Build automations by wiring triggers, actions, values and transforms together on a canvas, then let them run in the background — even when the app is closed. Expect rough edges, and expect macros to need rebuilding as things change.

### Added
- A node graph editor: drag triggers, actions, values and transforms onto a canvas and wire them together.
- 184 nodes covering sensors, location, messaging, calendar, files, photos, NFC, smart home, Home Assistant, MQTT, AI and more.
- A foreground service that runs macros in the background and re-arms them after a reboot.
- Home-screen widgets and launcher shortcuts for running a macro by hand.
- A JavaScript node for the things no other node covers, sandboxed in a WebView.
- Plugin support, so a separate app can contribute its own nodes.
- A process API, so other apps on the phone can start a macro with your consent.
- Import and export of macros as files.
- Eight languages: English, German, Spanish, French, Japanese, Portuguese, Russian and Simplified Chinese.
