#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_VERSION="${GRADLE_VERSION:-8.10.2}"
TOOLS_DIR="$ROOT_DIR/.tools"
GRADLE_HOME="$TOOLS_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
GRADLE_ZIP="$TOOLS_DIR/gradle-$GRADLE_VERSION-bin.zip"

if [[ ! -x "$GRADLE_BIN" ]]; then
  mkdir -p "$TOOLS_DIR"
  echo "Downloading Gradle $GRADLE_VERSION..."
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
  unzip -q -o "$GRADLE_ZIP" -d "$TOOLS_DIR"
fi

exec "$GRADLE_BIN" "$@"
