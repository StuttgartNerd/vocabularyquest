#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAPER_DIR="$ROOT_DIR/paper"
MC_VERSION="${1:-1.21.4}"
PAPER_API_BASE="https://api.papermc.io/v2/projects/paper/versions/$MC_VERSION"

if ! command -v jq >/dev/null 2>&1; then
  echo "This script requires jq. Install jq and run again."
  exit 1
fi

mkdir -p "$PAPER_DIR"

echo "Querying Paper builds for Minecraft $MC_VERSION..."
BUILDS_JSON="$(curl -fsSL "$PAPER_API_BASE/builds")"
BUILD_ID="$(printf '%s' "$BUILDS_JSON" | jq -r '.builds[-1].build')"
DOWNLOAD_NAME="$(printf '%s' "$BUILDS_JSON" | jq -r ".builds[] | select(.build == $BUILD_ID) | .downloads.application.name")"

if [[ -z "$BUILD_ID" || "$BUILD_ID" == "null" || -z "$DOWNLOAD_NAME" || "$DOWNLOAD_NAME" == "null" ]]; then
  echo "Could not resolve Paper build metadata for MC version $MC_VERSION."
  exit 1
fi

JAR_PATH="$PAPER_DIR/$DOWNLOAD_NAME"

echo "Downloading Paper build $BUILD_ID..."
curl -fL "$PAPER_API_BASE/builds/$BUILD_ID/downloads/$DOWNLOAD_NAME" -o "$JAR_PATH"
ln -sfn "$DOWNLOAD_NAME" "$PAPER_DIR/paper.jar"

echo "Paper downloaded to $JAR_PATH"
