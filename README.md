# App Android simple: TUN + sing-box (binario) + VLESS

Reestructurado para usar **ejecutable sing-box** (sin AAR).

## Flujo rápido

1. Coloca binarios en:
   - `app/src/main/assets/sing-box/arm64-v8a/sing-box`
   - `app/src/main/assets/sing-box/x86_64/sing-box`
2. Build app:
   - `gradle assembleDebug`
3. En runtime, la app copia el binario correcto (ABI) a `filesDir/sing-box`, da permiso de ejecución y lanza:
   - `sing-box run -c <config>`

## Workflow CI (2 etapas)

- **Stage 1**: prepara binarios oficiales de sing-box (`third_party/sing-box/...`).
- **Stage 2**: sincroniza binarios a assets y compila APK.

Archivo: `.github/workflows/android-two-stage.yml`

## Configuración VLESS

Edita `app/src/main/assets/singbox-config.json`:

- `TU_SERVER`
- `TU_UUID`
- `TU_SNI`

Outbound final: `orotoloco-vless`.

## Nota

Los binarios están ignorados en git para evitar errores de PR por archivos binarios.
