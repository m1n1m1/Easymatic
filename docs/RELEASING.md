# Cutting a release

Release notes are written in one place, [`CHANGELOG.md`](../CHANGELOG.md). The Play Store
text, the GitHub release and the website's changelog page are all generated from it, and
so is the app's own version number. You never edit a version in `build.gradle.kts`.

## Step 1: Write the entry

Add a section at the top of `CHANGELOG.md`, under `## [Unreleased]`:

```
## [0.2.0-alpha] - 2026-09-14
code: 200

Play: One short paragraph for the Play Store. 500 characters at most.

### Added
- What you added, one line per bullet.

### Fixed
- What you fixed.
```

`code:` is the Play `versionCode`. It has to be higher than every release before it —
Play rejects an upload that repeats one. Section headings come from a fixed list: Added,
Changed, Fixed, Removed, Deprecated and Security.

The version may carry a pre-release suffix, as `0.2.0-alpha` does. That suffix is the only
thing marking a release as a pre-release: the GitHub release is flagged from it, and the
website labels it. Drop the suffix and the same release is a final one.

> **Note:** the `Play:` paragraph is separate from the bullets because the Play Store cuts
> release notes off at 500 characters, and the other places have no limit. Leave it out and
> the bullets are used instead — which fails the build if they do not fit.

## Step 2: Regenerate

```
.\gradlew.bat :app:testDebugUnitTest --tests "*ChangelogExportTest*" -PregenerateChangelog=true
```

This writes `docs/changelog.generated.json` and the Play text under `fastlane/`. Both are
committed, and the same test fails the build when they no longer match `CHANGELOG.md`.

## Step 3: Commit, merge and tag

Day-to-day work lands on `develop`. A release is cut on a `release-<version>` branch,
merged into `main`, and tagged there:

```
git checkout -b release-0.2.0 develop
git commit -am "Release 0.2.0-alpha"
git checkout main
git merge --no-ff release-0.2.0
git tag v0.2.0-alpha
git push origin main release-0.2.0 v0.2.0-alpha
git checkout develop
git merge main
```

The tag creates the GitHub release, with the notes taken from the changelog. A tag that
does not name the newest entry is refused. The release branch stays, so a hotfix to that
version has a home.

## Step 4: Upload to Play

Build the bundle with `.\gradlew.bat :app:bundleRelease`, then upload it together with the
generated text in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. A
pre-release belongs on a closed testing track rather than production.

Signing reads `keystore.properties` at the repository root — gitignored, the same shape as
`local.properties`:

```
storeFile=C:/path/outside/the/repo/upload-key.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Create the key once with `keytool -genkeypair -v -keystore upload-key.jks -keyalg RSA
-keysize 2048 -validity 10000 -alias upload`, keep it outside the checkout, and back it up.
Enrol in Play App Signing so Google holds the key the shipped app is signed with; this one
only proves who uploaded the bundle, and can be replaced through the console if it is lost.

> **Note:** the upload itself is still manual. There is no Play service account and no
> publisher plugin, so nothing is pushed automatically. Without a `keystore.properties` the
> release variant still builds — it is simply unsigned, which Play rejects at upload.

## Step 5: Attach the APK to the GitHub release

A bundle cannot be installed on a phone, so the GitHub release carries an APK. Build it on
the tagged commit with the same key, give it the version in its name, and upload it:

```
.\gradlew.bat :app:assembleRelease
copy app\build\outputs\apk\release\app-release.apk Easymatic-0.2.0-alpha.apk
gh release upload v0.2.0-alpha Easymatic-0.2.0-alpha.apk
```

> **Note:** this APK is signed with the upload key, while Play signs what it ships with its
> own key. Android sees two different apps, so a phone cannot update from one to the other
> without uninstalling first.
