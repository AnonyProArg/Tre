# Local VPN Proxy (Android)

Aplicación Android simple que:

1. Muestra un botón para solicitar permiso de `VpnService`.
2. Crea una interfaz VPN virtual (TUN) al aceptar el permiso.
3. Captura paquetes en la interfaz y los reenvía al proxy local `127.0.0.1:1080`.

> Nota: este ejemplo reenvía bytes de paquetes IP al socket local. Para un proxy SOCKS5 real de producción se requiere implementar negociación de protocolo y manejo completo de flujos TCP/UDP.

## Compilar localmente

```bash
gradle assembleDebug
```

## Workflow CI

El workflow `.github/workflows/android-build.yml` compila el APK debug en cada push/PR usando Gradle sin wrapper binario en el repositorio.

## Binario sing-box (forma nativa clásica en Android)

El binario se empaqueta como librería nativa para que Android lo extraiga en `nativeLibraryDir` con permisos de ejecución.

De forma clásica, el proyecto lo prepara en build-time con Gradle (task `prepareSingBoxJniLibs`) a partir de `sing-box-1.13.2-android-arm64.tar.gz`, lo renombra a `libsingbox.so` y lo publica como `jniLibs` para `arm64-v8a`.

En runtime, la app lo ejecuta desde:

- `${applicationInfo.nativeLibraryDir}/libsingbox.so`

La resolución de ruta se hace en tiempo de ejecución (sin hardcodear `/data/app/...`) usando `applicationContext.applicationInfo.nativeLibraryDir` y el nombre `libsingbox.so`.

Si por políticas del dispositivo/instalación no aparece extraído en `nativeLibraryDir`, la app aplica fallback robusto: lee `lib*/libsingbox.so` desde el APK instalado (`sourceDir`/`splitSourceDirs`), lo copia a `files/native-bin/sing-box`, aplica permisos y ejecuta desde ahí.
