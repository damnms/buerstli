# Getting Bürstli into F-Droid

F-Droid builds apps itself from source. The app is added with a build recipe in the
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository; the store texts, icon,
screenshots and "what's new" come from `fastlane/metadata/android/` in this repo.

IzzyOnDroid is not an option: its inclusion policy rejects apps written fully or partly with
generative AI, and Bürstli is.

## When

F-Droid's inclusion policy (Quality Control, point 6) expects an app to have been actively
developed for at least 3 months, unless it has proven users or the author already maintains apps
on F-Droid. Bürstli started on 2026-10-29, so the plan is to submit **at the end of January 2027**,
with whatever shows that people use it (issues, reports, stars).

## Reproducible builds

Bürstli's release build is reproducible: v0.6.1 rebuilt with OpenJDK 17 and with OpenJDK 21 on
another machine was identical (apart from the signature) to the APK built and signed by the
GitHub release workflow with Temurin 17, checked with `apksigcopier compare`. The build workflow
keeps checking this on every push (two environments, compare checksums).

This matters because the decision is final: without reproducible builds F-Droid signs the app
with its own key, and that can't be changed later. With them, F-Droid verifies that its build
matches our release and then ships **our** signed APK, so users can switch freely between
GitHub, Obtainium and F-Droid.

## Steps

1. **Release a version that contains `fastlane/`** (0.6.1 or later), as described in
   [CONTRIBUTING.md](../CONTRIBUTING.md#releasing). At submission time, use the latest release.
2. **Fork fdroiddata on GitLab** (needs a GitLab account). The fork must be public and the
   branch must not be protected.
3. **Add `metadata/io.github.damnms.buerstli.yml`** with the recipe below. Replace version name,
   version code and `commit` with the latest release; the full hash of its tag comes from
   `git rev-parse vX.Y.Z^{commit}`.
4. **Push.** The fork's pipeline runs `fdroid lint`, `fdroid build` and friends. Fix whatever it
   reports – for example if F-Droid's category names have changed.
5. **Open a merge request** titled `New app: Bürstli`, use the template, tick the checklist and
   add the description below.

## Build recipe

```yaml
Categories:
  - Connectivity
License: GPL-3.0-only
AuthorName: damnms
SourceCode: https://github.com/damnms/buerstli
IssueTracker: https://github.com/damnms/buerstli/issues
Changelog: https://github.com/damnms/buerstli/blob/HEAD/CHANGELOG.md

AutoName: Bürstli

RepoType: git
Repo: https://github.com/damnms/buerstli.git

Binaries: https://github.com/damnms/buerstli/releases/download/v%v/buerstli-%v.apk

Builds:
  - versionName: 0.6.1
    versionCode: 10
    commit: <full commit hash of v0.6.1>
    subdir: app
    gradle:
      - yes

AllowedAPKSigningKeys: bac862212cc831d42e1ee3b7df5dd07e0635d2f807d957d357fd8d062b8b86ad

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.6.1
CurrentVersionCode: 10
```

Notes: no flavors, no Google libraries, no trackers, no `INTERNET` permission. `Binaries` points to
our signed release APK; F-Droid compares its own unsigned build against it and, if they match,
publishes ours. `AllowedAPKSigningKeys` is the SHA-256 of our signing certificate (`CN=damnms`),
from `apksigner verify --print-certs`. The dependency-info blob is disabled in
`app/build.gradle.kts`.

## Merge request description

> Bürstli controls Bluetooth toothbrushes without the vendor's account-bound app: Laifen Wave
> (tested on real hardware) and, experimental and read-only, Philips Sonicare. It has no INTERNET
> permission, no trackers and no proprietary dependencies.
>
> I'm the author. Reproducible builds are enabled: the release APK has been rebuilt identically in
> independent environments, and CI checks this on every push.
>
> In the spirit of transparency: the code was written with the help of an AI
> assistant (Claude). I reviewed it, and every feature was tested step by step on a real brush;
> the parser tests use packets captured from that brush. The README says the same.
