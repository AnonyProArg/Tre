# App Android simple: TUN + sing-box (binario) + VLESS

Listo para tu archivo ya descargado:

- `sing-box-1.13.2-android-arm64.tar.gz`

## Dónde colocarlo

Copia tu archivo aquí:

- `third_party/uploads/sing-box-1.13.2-android-arm64.tar.gz`

> Ese es el path que usa el script y el workflow.

## Flujo local

1. Preparar binario desde tu tar.gz:
   - `./scripts/build_libbox_from_official.sh`
2. Copiar binario a assets de la app:
   - `./scripts/sync_libbox_to_app.sh`
3. Compilar app:
   - `gradle assembleDebug`

## Workflow CI (2 etapas)

Archivo: `.github/workflows/android-two-stage.yml`

- **Stage 1** valida que exista el tar.gz en `third_party/uploads/`, lo extrae y prepara:
  - `third_party/sing-box/arm64-v8a/sing-box`
- **Stage 2** descarga ese artefacto, lo sincroniza a assets y compila APK.

## Ruta final del binario en app

- `app/src/main/assets/sing-box/arm64-v8a/sing-box`

## Configuración VLESS

Edita `app/src/main/assets/singbox-config.json`:

- `TU_SERVER`
- `TU_UUID`
- `TU_SNI`

Outbound final: `orotoloco-vless`.

## Corrección aplicada al cierre del servicio VPN

- El servicio ahora arranca como **foreground service** con notificación persistente.
- Se agregó manejo seguro de errores (si falta binario/config no crashea, loguea y se detiene limpio).
- Se agregaron logs del proceso `sing-box` a logcat (`[sing-box] ...`) para depuración real.

Si vuelve a cerrarse, revisa `logcat` filtrando por `TunVpnService` para ver la causa exacta.

## Nota

Los binarios siguen ignorados en git para evitar errores de PR por archivos binarios.

## Diagnóstico rápido si "no pasa nada"

Usa logcat:

```bash
adb logcat | rg "TunVpnService|\[sing-box\]"
```

Si ves `sing-box terminó: código X`, el proceso arrancó pero murió. Con binario puro en Android esto puede pasar por limitaciones de permisos/plataforma del modo tun en CLI; la app ahora lo deja visible en notificación y logs.
