#!/usr/bin/env bash
# Checks the Gradle wrapper against Gradle's official checksums:
# - gradle/wrapper/gradle-wrapper.jar, the only binary in the repo, must be the official jar;
# - distributionSha256Sum must be the official checksum of the distribution it downloads.
set -euo pipefail
cd "$(dirname "$0")/.."

props=gradle/wrapper/gradle-wrapper.properties
version=$(sed -n 's|^distributionUrl=.*/gradle-\(.*\)-bin\.zip$|\1|p' "$props")
[ -n "$version" ] || { echo "Can't read the Gradle version from $props" >&2; exit 1; }
base="https://services.gradle.org/distributions/gradle-$version"

official_jar=$(curl -sSfL "$base-wrapper.jar.sha256")
actual_jar=$(sha256sum gradle/wrapper/gradle-wrapper.jar | cut -d' ' -f1)
[ "$official_jar" = "$actual_jar" ] ||
  { echo "gradle-wrapper.jar does NOT match Gradle $version ($actual_jar != $official_jar)" >&2; exit 1; }

official_dist=$(curl -sSfL "$base-bin.zip.sha256")
pinned_dist=$(sed -n 's/^distributionSha256Sum=//p' "$props")
[ "$official_dist" = "$pinned_dist" ] ||
  { echo "distributionSha256Sum is not the official checksum of Gradle $version" >&2; exit 1; }

echo "Gradle wrapper $version: jar and distribution checksum are official."
