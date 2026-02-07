#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAPER_DIR="$ROOT_DIR/paper"

mkdir -p "$PAPER_DIR/plugins"

if [[ ! -f "$PAPER_DIR/paper.jar" ]]; then
  echo "paper/paper.jar not found."
  echo "Run: ./scripts/download-paper.sh"
  exit 1
fi

if [[ ! -f "$PAPER_DIR/eula.txt" ]]; then
  echo "eula=true" > "$PAPER_DIR/eula.txt"
fi

cd "$PAPER_DIR"
exec java ${JAVA_OPTS:--Xms1G -Xmx2G} -jar paper.jar --nogui
