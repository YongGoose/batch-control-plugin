#!/usr/bin/env bash
# Downloads the pinned JaCoCo agent and CLI (ci/jacoco.env) into ci/.cache/jacoco/ and verifies
# their sha256. Idempotent: a cached jar with the right checksum is kept, a wrong one is replaced.
# A checksum mismatch after download is fatal.
set -euo pipefail
CI="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$CI/jacoco.env"
DEST="$CI/.cache/jacoco"
mkdir -p "$DEST"
CENTRAL="${BC_MAVEN_CENTRAL:-https://repo1.maven.org/maven2}"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

fetch() { # <artifactId> <classifier> <sha256> <file name>
  local file="$DEST/$4"
  if [ -f "$file" ] && [ "$(sha256 "$file")" = "$3" ]; then
    echo "jacoco: $4 cached (sha256 ok)"
    return
  fi
  local url="$CENTRAL/org/jacoco/$1/$JACOCO_VERSION/$1-$JACOCO_VERSION-$2.jar"
  curl -fsSL --retry 3 -o "$file.part" "$url"
  local got
  got="$(sha256 "$file.part")"
  if [ "$got" != "$3" ]; then
    rm -f "$file.part"
    echo "jacoco: sha256 mismatch for $url: expected $3, got $got" >&2
    exit 1
  fi
  mv "$file.part" "$file"
  echo "jacoco: $4 $JACOCO_VERSION downloaded (sha256 ok)"
}

fetch org.jacoco.agent runtime "$JACOCO_AGENT_SHA256" jacocoagent.jar
fetch org.jacoco.cli nodeps "$JACOCO_CLI_SHA256" jacococli.jar
