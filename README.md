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
