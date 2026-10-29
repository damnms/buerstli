# Contributing

## Adding a toothbrush

Drivers are only marked `VERIFIED` after testing on real hardware with Bürstli. Without the brush,
a driver can still start as experimental and read-only if its protocol was documented and verified
on real brushes by someone else (see ADR-6 in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).

1. **Capture.** If Bürstli recognizes the brush (see `Drivers.kt`), its built-in report is the
   easiest start: services, readable values, advertisements and notifications, with address and
   serial number removed. Otherwise add a `KnownBrush` entry first. For details beyond that,
   nRF Connect or an HCI snoop log (Android developer options) of the official app help.
   Never copy code from a vendor app; describe the protocol in your own words.
2. **Test first.** Create `drivers/<brand>` (copy `drivers/laifen/build.gradle.kts`) and
   put your captured packets into a test, like `drivers/laifen/.../Packets.kt`.
   Write tests for decoding, recognizing the device name, and every command.
3. **Implement** a `Codec`, a `ToothbrushDriver` and a `BrushSession` until the tests pass.
   Start as `SupportLevel.EXPERIMENTAL`, and **read-only**: every capability `Access.READ_ONLY`,
   no commands. Writes are only enabled after someone tried them on a real brush.
4. **Register** the driver in `app/.../Drivers.kt` and add the module to `app/build.gradle.kts`.
5. **Try it** on the brush. If everything works, switch to `VERIFIED` and update the
   support table in the README.

If your brush needs a concept the domain doesn't have yet (a new parameter, a new
command), add it to `:domain` with tests, and explain why in the pull request.

## Changelog

[CHANGELOG.md](CHANGELOG.md) follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Every user-visible change gets a line under `## [Unreleased]`, in the section that fits
(`Added`, `Changed`, `Fixed`, `Removed`), written for users, not developers. CI fails if code
under `src/main/` changes without a changelog entry.

## Releasing

1. Rename `## [Unreleased]` to `## [x.y.z] - YYYY-MM-DD` and add a fresh empty `## [Unreleased]`.
2. Set `versionName = "x.y.z"` and increase `versionCode` in `app/build.gradle.kts`.
3. Run `scripts/changelog.sh fastlane`. It writes the short "what's new" that F-Droid and
   Obtainium show (`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, max. 500
   characters) from the changelog section.
4. Commit, then `git tag -a vx.y.z -m "Bürstli x.y.z" && git push origin main vx.y.z`.

The release workflow checks that tag, app version, changelog and store notes agree, builds and signs the APK,
and publishes the changelog section as release notes.

## Store listing

Title, descriptions, icon and screenshots for F-Droid and Obtainium live in
`fastlane/metadata/android/<locale>/` (English and German). Keep them in sync with the README's
list of supported brushes.

## Ground rules

- `./gradlew test lintDebug assembleDebug` must pass.
- No `INTERNET` permission, no analytics, no accounts. Ever.
- Commit messages: short imperative summary ("Add Oral-B iO driver").
