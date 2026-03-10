# Implementación estructurada (motor-only)

## Decisión principal

No usar `sing-box-for-android` como base de integración.

Se usa únicamente el motor oficial (`libbox.aar`) compilado desde `SagerNet/sing-box`, y luego nuestra app lo consume.

## Etapa 1: Build de engine

Script: `scripts/build_libbox_from_official.sh`

Pasos:

1. Clona `SagerNet/sing-box`.
2. Ejecuta:
   - `make lib_install`
   - `make lib_android`
3. Verifica artefactos:
   - `libbox.aar`
   - `libbox-legacy.aar`
4. Copia a `third_party/libbox/`.

## Etapa 2: Build de app

Script: `scripts/sync_libbox_to_app.sh`

- Copia AAR a `app/libs`.
- `app/build.gradle.kts` lo integra automáticamente si existe.
- `./gradlew assembleDebug` compila la app.

## Integración runtime actual

- `TunVpnService` valida config con `Libbox.checkConfig` cuando AAR está presente.
- Base mínima mantenida para avanzar a integración completa API (`CommandServer` + `PlatformInterface`) en siguiente iteración.

## Beneficio de este enfoque

- Flujo claro motor/app.
- Reproducible en CI.
- Sin dependencia de estructura interna de una app de terceros.
