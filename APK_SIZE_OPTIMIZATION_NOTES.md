# Motor experimental de reducción de APK

Esta rama (`experimental`) aplica una primera pasada de optimizaciones enfocadas en reducir tamaño sin cambiar la funcionalidad.

## Versión experimental v1 (actual)

- R8 en modo completo (`android.enableR8.fullMode=true`).
- `R` no transitiva y no final para reducir bytecode generado (`android.nonTransitiveRClass`, `android.nonFinalResIds`).
- `buildConfig` deshabilitado para no generar clase innecesaria.
- `release` reforzado con `debugSymbolLevel = NONE`, `isDebuggable = false`, `isJniDebuggable = false`.
- Recorte de recursos por idioma: solo `es` y `en`.
- Exclusión de metadatos `META-INF` no necesarios en runtime.
- Reglas ProGuard/R8 para reempaquetado y eliminación de llamadas de `android.util.Log`.

## Próximas versiones sugeridas

- v2: comparar APK firmado vs unsigned y medir impacto de zipalign/apksigner + configuración de compresión por extensión.
- v3: evaluar migración de layout para eliminar dependencias de UI pesadas si es posible sin alterar UX.
- v4: analizar reducción del AAR de `libbox` (strip de símbolos, recursos y clases no usadas) con pruebas funcionales.
