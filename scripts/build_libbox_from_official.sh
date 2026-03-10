#!/usr/bin/env bash
set -euo pipefail

# Build official libbox AAR directly from sing-box upstream source.
# No need to clone sing-box-for-android app repository.
# Requirements: git, go, java17, ANDROID_NDK_HOME, Android SDK cmdline-tools.

SING_BOX_REF="${SING_BOX_REF:-dev-next}"
WORKDIR="${WORKDIR:-$(pwd)/.tmp/upstream-build}"
OUTDIR="${OUTDIR:-$(pwd)/third_party/libbox}"

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR" "$OUTDIR"

pushd "$WORKDIR" >/dev/null
  git clone --depth 1 --branch "$SING_BOX_REF" https://github.com/SagerNet/sing-box.git

  pushd sing-box >/dev/null
    make lib_install
    make lib_android

    test -f libbox.aar
    test -f libbox-legacy.aar

    cp libbox.aar "$OUTDIR/libbox.aar"
    cp libbox-legacy.aar "$OUTDIR/libbox-legacy.aar"
  popd >/dev/null
popd >/dev/null

echo "AARs generated in: $OUTDIR"
