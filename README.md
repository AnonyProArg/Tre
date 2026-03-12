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

## Workflow CI

El workflow `.github/workflows/android-build.yml` ejecuta 2 jobs:

1. `build-libbox`: compila `libbox.aar` y `libbox-sources.jar` desde `SagerNet/sing-box`.
2. `build-apk`: descarga esos artifacts a `app/libs/libbox/` y compila el APK debug.

## Flujo runtime actual

- `MainActivity` muestra HWID, permite copiarlo, configurar/guardar el dominio del túnel y hace auth antes de pedir permiso VPN.
- `LocalVpnService` usa el dominio guardado, vuelve a validar auth, inicia el proxy local y luego arranca `libbox`.
- El socket del túnel remoto se protege con `VpnService.protect(socket)` para evitar loop de ruteo.
