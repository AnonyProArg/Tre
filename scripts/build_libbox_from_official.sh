#!/usr/bin/env bash
set -euo pipefail

# Legacy filename kept to avoid breaking callers.
# New behavior: prepare sing-box executable from a local uploaded tar.gz.

INPUT_TGZ="${INPUT_TGZ:-$(pwd)/third_party/uploads/sing-box-1.13.2-android-arm64.tar.gz}"
OUTDIR="${OUTDIR:-$(pwd)/third_party/sing-box/arm64-v8a}"

if [[ ! -f "$INPUT_TGZ" ]]; then
  echo "ERROR: input tarball not found: $INPUT_TGZ"
  echo "Upload your file here first: third_party/uploads/sing-box-1.13.2-android-arm64.tar.gz"
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$OUTDIR"
tar -xzf "$INPUT_TGZ" -C "$tmp"

binary_path="$(find "$tmp" -type f -name sing-box | head -n 1)"
if [[ -z "$binary_path" ]]; then
  echo "ERROR: sing-box binary not found inside tarball"
  exit 1
fi

cp "$binary_path" "$OUTDIR/sing-box"
chmod +x "$OUTDIR/sing-box"

echo "Prepared arm64 binary in: $OUTDIR/sing-box"
