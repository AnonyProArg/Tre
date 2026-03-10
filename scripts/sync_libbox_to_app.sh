#!/usr/bin/env bash
set -euo pipefail

# Legacy filename kept to avoid breaking callers.
# New behavior: sync sing-box executable binaries into app assets.

SRC_DIR="${1:-third_party/sing-box}"
DST_DIR="app/src/main/assets/sing-box"

mkdir -p "$DST_DIR/arm64-v8a" "$DST_DIR/x86_64"
cp "$SRC_DIR/arm64-v8a/sing-box" "$DST_DIR/arm64-v8a/sing-box"
cp "$SRC_DIR/x86_64/sing-box" "$DST_DIR/x86_64/sing-box"
chmod +x "$DST_DIR/arm64-v8a/sing-box" "$DST_DIR/x86_64/sing-box"

echo "Copied sing-box binaries to $DST_DIR"
