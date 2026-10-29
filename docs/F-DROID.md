# Getting Bürstli into F-Droid

F-Droid builds apps itself from source. The app is added with a build recipe in the
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository; the store texts, icon,
screenshots and "what's new" come from `fastlane/metadata/android/` in this repo.

IzzyOnDroid is not an option: its inclusion policy rejects apps written fully or partly with
generative AI, and Bürstli is.

## Steps

1. **Release a version that contains `fastlane/`** (0.6.1 or later), as described in
   [CONTRIBUTING.md](../CONTRIBUTING.md#releasing).
2. **Fork fdroiddata on GitLab** (needs a GitLab account). The fork must be public and the
   branch must not be protected.
3. **Add `metadata/io.github.damnms.buerstli.yml`** with the recipe below. Replace `commit` with
   the full hash of the release tag: `git rev-parse v0.6.1^{commit}`.
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

Builds:
  - versionName: 0.6.1
    versionCode: 10
    commit: <full commit hash of v0.6.1>
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.6.1
CurrentVersionCode: 10
```

Notes: no flavors, no Google libraries, no trackers, no `INTERNET` permission. The release build
is unsigned unless the `BUERSTLI_KEYSTORE*` variables are set, so F-Droid signs it with its own key.
The dependency-info blob is already disabled in `app/build.gradle.kts`.

## Merge request description

> Bürstli controls Bluetooth toothbrushes without the vendor's account-bound app: Laifen Wave
> (tested on real hardware) and, experimental and read-only, Philips Sonicare. It has no INTERNET
> permission, no trackers and no proprietary dependencies.
>
> I'm the author. In the spirit of transparency: the code was written with the help of an AI
> assistant (Claude). I reviewed it, and every feature was tested step by step on a real brush;
> the parser tests use packets captured from that brush. The README says the same.

## Later: reproducible builds

With F-Droid's signature, people who installed Bürstli from GitHub or Obtainium can't update to
the F-Droid build without uninstalling first. If the build is made byte-for-byte reproducible,
F-Droid can ship the APK signed with our own key instead (`Binaries:` and
`AllowedAPKSigningKeys:` in the recipe). Worth doing once the app is listed.
