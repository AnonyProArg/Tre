#!/usr/bin/env bash
set -euo pipefail

# Legacy filename kept to avoid breaking callers.
# New behavior: sync arm64 sing-box executable binary into app assets.

SRC_DIR="${1:-third_party/sing-box/arm64-v8a}"
DST_DIR="app/src/main/assets/sing-box/arm64-v8a"

mkdir -p "$DST_DIR"

if [[ ! -f "$SRC_DIR/sing-box" ]]; then
  echo "ERROR: missing source binary: $SRC_DIR/sing-box"
  exit 1
fi

cp "$SRC_DIR/sing-box" "$DST_DIR/sing-box"
chmod +x "$DST_DIR/sing-box"

echo "Copied sing-box binary to $DST_DIR/sing-box"
