#!/usr/bin/env bash
set -euo pipefail

# Legacy filename kept to avoid breaking callers.
# New behavior: fetch/build sing-box executable binaries (not AAR).

SINGBOX_VERSION="${SINGBOX_VERSION:-1.13.6}"
OUTDIR="${OUTDIR:-$(pwd)/third_party/sing-box}"

mkdir -p "$OUTDIR"

fetch_one() {
  local arch="$1" abi="$2"
  local url="https://github.com/SagerNet/sing-box/releases/download/v${SINGBOX_VERSION}/sing-box-${SINGBOX_VERSION}-android-${arch}.tar.gz"
  local tmp
  tmp="$(mktemp -d)"
  curl -fL "$url" -o "$tmp/singbox.tgz"
  tar -xzf "$tmp/singbox.tgz" -C "$tmp"
  mkdir -p "$OUTDIR/$abi"
  cp "$tmp/sing-box-${SINGBOX_VERSION}-android-${arch}/sing-box" "$OUTDIR/$abi/sing-box"
  chmod +x "$OUTDIR/$abi/sing-box"
  rm -rf "$tmp"
  echo "Prepared $abi binary"
}

fetch_one arm64 arm64-v8a
fetch_one amd64 x86_64

echo "Binaries generated in: $OUTDIR"
