# Motor experimental de reducción de APK

Esta rama (`experimental`) aplica iteraciones enfocadas en reducir tamaño sin cambiar la funcionalidad.

## Versión experimental v1

- R8 en modo completo (`android.enableR8.fullMode=true`).
- `R` no transitiva y no final para reducir bytecode generado (`android.nonTransitiveRClass`, `android.nonFinalResIds`).
- `buildConfig` deshabilitado para no generar clase innecesaria.
- `release` reforzado con `debugSymbolLevel = NONE`, `isDebuggable = false`, `isJniDebuggable = false`.
- Recorte de recursos por idioma: solo `es` y `en`.
- Exclusión de metadatos `META-INF` no necesarios en runtime.
- Reglas ProGuard/R8 para reempaquetado y eliminación de llamadas de `android.util.Log`.

## Versión experimental v2 (motor libbox)

- En CI, después de generar/recuperar `libbox.aar`, se mantiene solo `jni/arm64-v8a`.
- Se hace stripping de símbolos nativos en todos los `*.so` del AAR (`llvm-strip --strip-unneeded` o `strip` como fallback).
- El AAR se reempaqueta con compresión máxima (`zip -9`).
- El workflow reporta tamaño antes/después para medir ahorro real por corrida.

## Próximas versiones sugeridas

- v3: evaluar tags/flags de build de sing-box para excluir features no usadas (si se validan funcionalmente para VLESS).
- v4: generar benchmarks automáticos en CI comparando tamaño APK final entre corridas.
