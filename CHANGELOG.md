# Changelog

All notable changes to Ottomatic are recorded here. The format follows
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

## [0.1.0-alpha] - 2026-08-30
code: 100

Play: The first alpha of Ottomatic. Build automations by wiring triggers, actions, values and transforms together on a canvas, then let them run in the background — even when the app is closed. Expect rough edges, and expect macros to need rebuilding as things change.

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
