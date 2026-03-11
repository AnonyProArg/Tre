#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Uso: $0 <directorio_con_artifacts_libbox>"
  exit 1
fi

SRC_DIR="$1"
DEST_DIR="app/libs/libbox"

mkdir -p "$DEST_DIR"

if [[ -f "$SRC_DIR/libbox.aar" ]]; then
  cp "$SRC_DIR/libbox.aar" "$DEST_DIR/libbox.aar"
  echo "Copiado: libbox.aar"
else
  echo "No encontrado: $SRC_DIR/libbox.aar"
fi

if [[ -f "$SRC_DIR/libbox-sources.jar" ]]; then
  cp "$SRC_DIR/libbox-sources.jar" "$DEST_DIR/libbox-sources.jar"
  echo "Copiado: libbox-sources.jar"
else
  echo "No encontrado: $SRC_DIR/libbox-sources.jar"
fi

echo "Destino final: $DEST_DIR"
ls -lah "$DEST_DIR"
