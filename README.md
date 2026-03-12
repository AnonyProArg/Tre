# Local VPN Proxy (Android)

Aplicación Android simple que:

1. Muestra un botón para solicitar permiso de `VpnService`.
2. Crea una interfaz VPN virtual (TUN) al aceptar el permiso.
3. Inicia `libbox` desde un `libbox.aar` y enruta tráfico al proxy local `127.0.0.1:1080`.

## Compilar localmente

```bash
gradle assembleDebug
```

## Workflow CI

El workflow `.github/workflows/android-build.yml` ejecuta un flujo de 2 jobs:

1. `build-libbox`: compila `libbox.aar` y `libbox-sources.jar` desde `SagerNet/sing-box`.
2. `build-apk`: descarga esos artifacts a `app/libs/libbox/` y compila el APK debug.

## Flujo de artifacts `libbox.aar`

Para local/manual puedes seguir usando:

```bash
scripts/install_libbox_artifacts.sh <directorio_descargado_del_artifact>
```

La app carga automáticamente `*.aar` desde `app/libs/libbox/` vía Gradle.

## Runtime engine

La app usa únicamente `libbox` (AAR) mediante `CommandServer` + `PlatformInterface`.
