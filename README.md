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

Nota de compatibilidad: en esta versión se ejecuta `sing-box run -c <config>` sin `--force-passive-tun`, porque el binario actual reporta ese flag como no soportado (`unknown flag`).

La ejecución ahora prueba variantes de comando para adaptarse a cambios entre versiones de sing-box: inspecciona `sing-box run -h`, usa `--force-passive-tun` solo si existe, y si un intento termina enseguida prueba la siguiente variante sin romper el servicio.

Además se migró la sección `dns.servers` al formato nuevo (`type: local`) para compatibilidad con sing-box 1.12+ y evitar el fatal de `legacy DNS servers`.

Como red de seguridad para pruebas, la app también reintenta cada variante activando el entorno `ENABLE_DEPRECATED_LEGACY_DNS_SERVERS=true` por si el binario o perfil requiere compatibilidad temporal.

Se cambió la estrategia TUN para Android VPN: la app crea la interfaz con `VpnService.Builder.establish()`, duplica su FD al descriptor esperado por sing-box (FD 7) y escribe `"fd": 7` en el inbound `tun`. Así sing-box reutiliza el TUN del sistema y no intenta crearlo por su cuenta.
