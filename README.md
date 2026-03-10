# App Android funcional (base): TUN + sing-box + VLESS (orotoloco)

Gracias por la aclaración: este proyecto queda estructurado para usar **solo el motor oficial** de sing-box.

- ✅ Se compila el engine `libbox.aar` desde upstream de `sing-box`.
- ✅ No se usa `sing-box-for-android` como dependencia del workflow de motor.
- ✅ La app propia consume el `.aar` y compila su APK.

---

## Arquitectura mínima

- `MainActivity`: botón único de conectar y permiso de VPN.
- `TunVpnService`: levanta TUN y arranca flujo base.
- `singbox-config.json`: salida VLESS (`orotoloco-vless`).
- `libbox.aar`: motor oficial Android integrado en nuestra app.

## Workflow recomendado (2 etapas)

### Etapa 1: compilar motor (`.aar`) desde sing-box

```bash
./scripts/build_libbox_from_official.sh
```

Qué hace:

- clona `SagerNet/sing-box`
- ejecuta `make lib_install`
- ejecuta `make lib_android`
- toma `libbox.aar` y `libbox-legacy.aar` generados por ese repo
- los guarda en `third_party/libbox/`

### Etapa 2: consumir motor en nuestra app

```bash
./scripts/sync_libbox_to_app.sh
./gradlew assembleDebug
```

La app detecta `app/libs/libbox.aar` y lo integra automáticamente.

---

## CI/CD (dos etapas)

Archivo: `.github/workflows/android-two-stage.yml`

- **Job stage1-build-libbox-aar**
  - build de engine oficial `.aar` (solo motor)
  - publica artefacto `libbox-aar`
- **Job stage2-build-android-app**
  - descarga artefacto `.aar`
  - sincroniza a `app/libs`
  - compila APK debug

---

## Estado funcional actual

- UI con botón conectar.
- Servicio TUN base.
- Validación de JSON con `Libbox.checkConfig(...)` cuando el AAR está presente.
- ABIs 64-bit configuradas (`arm64-v8a`, `x86_64`).

## Configurar VLESS real

Edita `app/src/main/assets/singbox-config.json`:

- `TU_SERVER`
- `TU_UUID`
- `TU_SNI`

Con eso, la salida final sigue siendo `orotoloco-vless`.

---

## Referencias técnicas

- Motor oficial: `https://github.com/SagerNet/sing-box`
- AAR público de referencia: `https://github.com/singbox-android/libbox`
