#!/usr/bin/env bash
# Prepares a release commit, see CONTRIBUTING.md:
#
#   scripts/release.sh x.y.z
#
# Moves the [Unreleased] entries to [x.y.z] with today's date, raises versionCode and sets
# versionName, writes the store notes, runs the release check and commits it all, new files
# included. Stops and undoes its changes if the store notes would be cut. Tagging and pushing stay
# with you, the script prints the command.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "$*" >&2; exit 1; }

version="${1:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: scripts/release.sh x.y.z"
[ -z "$(git status --porcelain)" ] || die "The working tree isn't clean; commit or stash first."
grep -qx '## \[Unreleased\]' CHANGELOG.md || die "CHANGELOG.md has no '## [Unreleased]' heading."
! grep -q "^## \[$version\]" CHANGELOG.md || die "CHANGELOG.md already has a section [$version]."
scripts/changelog.sh notes Unreleased > /dev/null 2>&1 || die "Nothing under '## [Unreleased]' to release."

old_code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
[ -n "$old_code" ] || die "No versionCode in app/build.gradle.kts."
code=$((old_code + 1))
store="fastlane/metadata/android/en-US/changelogs/$code.txt"
[ ! -e "$store" ] || die "$store exists already."

undo() {
  git checkout -- CHANGELOG.md app/build.gradle.kts
  rm -f "$store"
}
trap 'undo; echo "Nothing changed." >&2' ERR

sed -i "0,/^## \[Unreleased\]\$/s//## [Unreleased]\n\n## [$version] - $(date +%Y-%m-%d)/" CHANGELOG.md
sed -i -e "s/versionCode = $old_code\b/versionCode = $code/" \
  -e "s/versionName = \"[^\"]*\"/versionName = \"$version\"/" app/build.gradle.kts

scripts/changelog.sh fastlane > /dev/null 2>&1
if grep -q '^More: ' "$store"; then
  trap - ERR
  undo
  die "The [$version] entries are longer than the 500 characters stores show. Shorten them in
CHANGELOG.md (under [Unreleased]) and run this again. Nothing changed."
fi
scripts/changelog.sh check-release "$version"

git add CHANGELOG.md app/build.gradle.kts "$store"
git commit -q -m "Release $version"
trap - ERR

echo
echo "Committed \"Release $version\" (versionCode $code). Check it, then:"
echo "  git tag -a v$version -m \"Bürstli $version\" && git push origin main v$version"
