# Local VPN Proxy (Android)

Aplicación Android en modo pruebas para validar el flujo completo:

1. Genera/lee HWID local (`filesDir/hwid.txt`).
2. Hace auth contra servidor remoto con ese HWID.
3. Levanta proxy local en `127.0.0.1:10800`.
4. Inicia `libbox` (AAR) con TUN y outbound VLESS apuntando al proxy local.
5. Muestra logs en vivo y estado de cuenta en la UI.

## Compilar localmente

```bash
gradle assembleDebug
```

> Requisito: usar JDK 17 para evitar errores de compatibilidad durante la compilación.

## Workflow CI

El workflow `.github/workflows/android-build.yml` ejecuta 2 jobs:

1. `build-libbox`: compila `libbox.aar` y `libbox-sources.jar` desde `SagerNet/sing-box`.
2. `build-apk`: descarga esos artifacts a `app/libs/libbox/` y compila el APK debug.

## Flujo runtime actual

- `MainActivity` muestra HWID, permite copiarlo, configurar/guardar dominio del túnel, TUN stack (`system/gvisor/mixed`) y `smux max_streams`, y hace auth antes de pedir permiso VPN.
- `LocalVpnService` usa la sesión validada, inicia proxy local + libbox y corre como foreground service para evitar muerte al salir de multitarea.
- El socket del túnel remoto se protege con `VpnService.protect(socket)` para evitar loop de ruteo.


### Fallbacks de canal BlackTunnel

- Intento 1: IPv6 hardcodeado al nodo CF zero-rated.
- Intento 2: IPv6 resuelto por DNS del host señuelo.
- Intento 3: IPv4 directo al dominio configurado (sin payload señuelo p1).
- El parser usa la respuesta HTTP útil con `101` cuando CF concatena `530 + 101`.
