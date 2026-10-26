#!/usr/bin/env bash
# Fetches Android builds of OpenJDK (8 / 17 / 21) to bundle in the APK.
# They come from PojavLauncherTeam/android-openjdk-build-multiarch (GPLv2 +
# Classpath Exception) as workflow artifacts: universal.tar.xz + bin-<arch>.tar.xz.
# Missing JREs are not fatal: the app can import a JRE or download one later.
# Usage: fetch-jre.sh <assets/runtime dir>   (needs GH_TOKEN for the GitHub API)
set -uo pipefail
OUT=$(realpath -m "${1:?out dir}")
REPO=${JRE_REPO:-PojavLauncherTeam/android-openjdk-build-multiarch}
ARCH=${JRE_ARCH:-aarch64}
mkdir -p "$OUT"
TMP=$(mktemp -d)

echo "Artifacts in $REPO:"
gh api "repos/$REPO/actions/artifacts?per_page=100" --jq '.artifacts[] | select(.expired==false) | "\(.id) \(.name) \(.created_at)"' > "$TMP/list" || true
head -40 "$TMP/list"

for major in 8 17 21; do
  id=$(grep -E " jre${major}(-pojav)?( |$)" "$TMP/list" | head -1 | cut -d' ' -f1)
  [ -z "$id" ] && id=$(grep -E " .*jre${major}[^0-9]" "$TMP/list" | head -1 | cut -d' ' -f1)
  if [ -z "$id" ]; then echo "::warning::no artifact for Java $major"; continue; fi
  d="$TMP/jre$major"
  mkdir -p "$d"
  if ! gh api "repos/$REPO/actions/artifacts/$id/zip" > "$d/a.zip"; then echo "::warning::download failed for Java $major"; continue; fi
  (cd "$d" && unzip -q a.zip && rm a.zip)
  ls -la "$d"
  files=()
  for f in universal.tar.xz "bin-$ARCH.tar.xz" version; do [ -f "$d/$f" ] && files+=("$f"); done
  if [ ! -f "$d/bin-$ARCH.tar.xz" ]; then echo "::warning::Java $major has no $ARCH build"; continue; fi
  # one archive per JRE; the core unpacks the nested universal/bin archives itself
  tar -C "$d" -cf - "${files[@]}" | xz -0 -T0 > "$OUT/jre$major.tar.xz"
  echo "::notice::bundled Java $major ($(du -h "$OUT/jre$major.tar.xz" | cut -f1))"
done
ls -la "$OUT"
