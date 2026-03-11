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

En CI, el workflow descomprime `sing-box-1.13.2-android-arm64.tar.gz`, renombra `sing-box` a `libsingbox.so` y lo coloca en:

- `app/src/main/jniLibs/arm64-v8a/libsingbox.so`

En runtime, la app lo ejecuta desde:

- `${applicationInfo.nativeLibraryDir}/libsingbox.so`
