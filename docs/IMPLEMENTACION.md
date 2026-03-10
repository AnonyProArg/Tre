# Implementación binaria (arm64 con archivo local)

## Objetivo

Usar tu archivo local `sing-box-1.13.2-android-arm64.tar.gz` para construir rápido una app funcional.

## Estructura preparada

- Upload del archivo:
  - `third_party/uploads/sing-box-1.13.2-android-arm64.tar.gz`
- Binario extraído:
  - `third_party/sing-box/arm64-v8a/sing-box`
- Binario empaquetado en app:
  - `app/src/main/assets/sing-box/arm64-v8a/sing-box`

## Scripts

- `scripts/build_libbox_from_official.sh`
  - toma el tar.gz local de `third_party/uploads/`
  - extrae `sing-box`
  - lo deja ejecutable en `third_party/sing-box/arm64-v8a/sing-box`
- `scripts/sync_libbox_to_app.sh`
  - copia el binario a assets de la app

## Servicio VPN

- `TunVpnService` arranca como foreground service antes de conectar.
- Copia binario a `filesDir/sing-box`, asigna permisos de ejecución y lanza `run -c`.
- Captura stdout/stderr del proceso y lo manda a logcat para debug.
- Si falta asset/binario o falla inicialización, detiene el servicio de forma controlada.

## CI

Workflow 2 etapas:

1. preparar binario desde tar.gz subido
2. compilar app con ese binario
