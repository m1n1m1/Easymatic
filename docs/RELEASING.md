# Cutting a release

Release notes are written in one place, [`CHANGELOG.md`](../CHANGELOG.md). The Play Store
text, the GitHub release and the website's changelog page are all generated from it, and
so is the app's own version number. You never edit a version in `build.gradle.kts`.

## Step 1: Write the entry

Fetch remote changes and fast-forward local `develop` and `main` before starting.
Create a `feature/release-<version>` branch from the updated `develop`; make all
release-note and workflow edits there, then merge them into `develop` in step 3.

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

## Step 3: Update develop, then main, then create the release branch

`develop` always contains the newest work, including release notes. `main` holds the
latest released state. Only after updating both do we create `release-<version>`
(without the pre-release suffix) from `main`, preserving the state at release time.
Run verification on the preparation branch before merging:

```
.\gradlew.bat assembleDebug test detekt lintDebug
git add CHANGELOG.md docs/changelog.generated.json fastlane/metadata/android/en-US/changelogs/200.txt
git commit -m "Release 0.2.0-alpha"
git switch develop
git merge --ff-only feature/release-0.2.0-alpha
git push origin develop
git switch main
git merge --ff-only develop
git push origin main
git switch -c release-0.2.0 main
git push -u origin release-0.2.0
git tag -a v0.2.0-alpha -m "Easymatic 0.2.0-alpha"
git push origin v0.2.0-alpha
```

These commands describe the branch order. When branch protection requires a pull request,
push the preparation branch and merge an approved PR into `develop` instead of pushing
directly. A locked `main` requires a maintainer to authorize and perform its update;
restore the lock afterwards. Do not disable protections as part of the release workflow.

Include any release-process edits in the preparation commit as well. If a fast-forward
fails, reconcile the branches on a feature branch and merge that into `develop` first.
Do not reset a branch or overwrite newer remote work.

The tag creates the GitHub release, with the notes taken from the changelog. A tag that
does not name the newest entry on its tagged commit is refused. The workflow also
requires that commit to be reachable from `develop`, `main` and its release branch.
Push the branches before the tag. Manual workflow dispatch checks the notes without
publishing and does not require these branch checks.

The release branch stays at its release state until a hotfix is needed; never merge
ongoing development into it. Prepare a hotfix on a feature branch based on the release
branch, then merge the fix and its regenerated notes back into the release branch.
Use a new version, version code and tag; never move an existing tag. A suffix-only
update (for example `0.2.0-alpha.1`) uses the same `release-0.2.0` branch. If the numeric
version changes, create the corresponding release branch at the hotfix commit too.
Merge the hotfix ancestry into `develop` and `main` before pushing its tag, keeping any
newer release first in their changelogs and preserving the latest release state on
`main`. Resolve conflicts on feature branches before updating these integration branches.

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
