# Implementación binaria (sin AAR)

## Objetivo

Usar sing-box como ejecutable empaquetado en assets y ejecutarlo desde `VpnService`.

## Estructura

- Assets por ABI:
  - `app/src/main/assets/sing-box/arm64-v8a/sing-box`
  - `app/src/main/assets/sing-box/x86_64/sing-box`
- Servicio:
  - `TunVpnService` crea TUN
  - copia binario correcto por ABI a `filesDir/sing-box`
  - `chmod +x`
  - ejecuta `run -c singbox-config.json`

## Scripts

- `scripts/build_libbox_from_official.sh`: prepara binarios oficiales en `third_party/sing-box`.
- `scripts/sync_libbox_to_app.sh`: mueve binarios de `third_party/sing-box` a assets.

## CI

Workflow 2 etapas:

1. preparar binarios
2. compilar app con esos binarios
