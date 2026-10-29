#!/usr/bin/env bash
# Changelog checks for CI, see CONTRIBUTING.md. Format: https://keepachangelog.com/en/1.1.0/
#
#   changelog.sh notes VERSION          print the release notes of VERSION
#   changelog.sh check-release VERSION  tag, app version and changelog must agree
#   changelog.sh check-updated BASE HEAD  user-visible code changed => CHANGELOG.md too
#   changelog.sh fastlane               write the store "what's new" for the current app version
set -euo pipefail
cd "$(dirname "$0")/.."

CHANGELOG=CHANGELOG.md

section() { # body of "## [VERSION]" up to the next "## [", blank lines trimmed
  awk -v v="$1" '
    $0 ~ "^## \\[" { if (found) exit; if (index($0, "## [" v "]") == 1) { found = 1; next } }
    found { print }
  ' "$CHANGELOG" | sed -e '/./,$!d' | tac | sed -e '/./,$!d' | tac
}

app_version() {
  sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts
}

app_version_code() {
  sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts
}

store_changelog() { # path of the F-Droid/Obtainium "what's new" for the current version code
  echo "fastlane/metadata/android/en-US/changelogs/$(app_version_code).txt"
}

store_text() {
  section "$1" | python3 scripts/fastlane_changelog.py
}

case "${1:-}" in
  notes)
    notes=$(section "$2")
    [ -n "$notes" ] || { echo "No entries for [$2] in $CHANGELOG" >&2; exit 1; }
    echo "$notes"
    ;;
  check-release)
    version="$2"
    [ "$(app_version)" = "$version" ] ||
      { echo "Tag says $version, but app/build.gradle.kts says versionName = \"$(app_version)\"" >&2; exit 1; }
    grep -qE "^## \[$version\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$" "$CHANGELOG" ||
      { echo "$CHANGELOG has no '## [$version] - YYYY-MM-DD' heading" >&2; exit 1; }
    [ -n "$(section "$version")" ] || { echo "Section [$version] in $CHANGELOG is empty" >&2; exit 1; }
    file=$(store_changelog)
    [ -f "$file" ] || { echo "$file is missing, run: scripts/changelog.sh fastlane" >&2; exit 1; }
    [ "$(cat "$file")" = "$(store_text "$version")" ] ||
      { echo "$file doesn't match CHANGELOG.md, run: scripts/changelog.sh fastlane" >&2; exit 1; }
    echo "Version $version: tag, app, changelog and store notes agree."
    ;;
  check-updated)
    base="$2" head="$3"
    if [ -z "$base" ] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
      echo "No usable base commit ($base), skipping."; exit 0
    fi
    changed=$(git diff --name-only "$base" "$head")
    # User-visible = production code and resources; tests, docs and build files don't count.
    visible=$(echo "$changed" | grep -E '(^|/)src/main/' || true)
    if [ -n "$visible" ] && ! echo "$changed" | grep -qx "$CHANGELOG"; then
      echo "User-visible files changed, but $CHANGELOG did not:" >&2
      echo "$visible" | sed 's/^/  /' >&2
      echo "Add an entry under '## [Unreleased]'." >&2
      exit 1
    fi
    echo "Changelog check passed."
    ;;
  fastlane)
    version=$(app_version)
    [ -n "$(section "$version")" ] || { echo "No entries for [$version] in $CHANGELOG" >&2; exit 1; }
    file=$(store_changelog)
    store_text "$version" > "$file"
    echo "Wrote $file ($(wc -c < "$file") bytes):"
    cat "$file"
    ;;
  *)
    sed -n '2,8p' "$0"; exit 2
    ;;
esac
