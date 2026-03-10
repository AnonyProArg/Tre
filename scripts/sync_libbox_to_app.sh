#!/usr/bin/env bash
set -euo pipefail

SRC_DIR="${1:-third_party/libbox}"
DST_DIR="app/libs"

mkdir -p "$DST_DIR"
cp "$SRC_DIR/libbox.aar" "$DST_DIR/libbox.aar"
if [[ -f "$SRC_DIR/libbox-legacy.aar" ]]; then
  cp "$SRC_DIR/libbox-legacy.aar" "$DST_DIR/libbox-legacy.aar"
fi

echo "Copied libbox AAR(s) to $DST_DIR"
