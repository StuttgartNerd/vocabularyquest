#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_BUILD_DIR="$ROOT_DIR/plugin-vocabulary-quest/build/libs"
PAPER_PLUGIN_DIR="$ROOT_DIR/paper/plugins"

mkdir -p "$PAPER_PLUGIN_DIR"

# Keep only the current plugin artifact to avoid loading duplicate plugin variants.
rm -f "$PAPER_PLUGIN_DIR"/hello-plugin-*.jar "$PAPER_PLUGIN_DIR"/vocabulary-quest-plugin-*.jar

if [[ ! -d "$PLUGIN_BUILD_DIR" ]]; then
  echo "Plugin build directory not found: $PLUGIN_BUILD_DIR"
  echo "Build it first with:"
  echo "  ./scripts/gradle.sh :plugin-vocabulary-quest:build"
  exit 1
fi

LATEST_JAR="$(ls -1t "$PLUGIN_BUILD_DIR"/*.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "$LATEST_JAR" ]]; then
  echo "No plugin jar found in $PLUGIN_BUILD_DIR"
  exit 1
fi

cp "$LATEST_JAR" "$PAPER_PLUGIN_DIR/"
echo "Copied $(basename "$LATEST_JAR") to $PAPER_PLUGIN_DIR"
