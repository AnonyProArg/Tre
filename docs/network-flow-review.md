# Revisión técnica de flujo de red y estabilidad (Local VPN + proxy + libbox)

Fecha: 2026-03-13  
Alcance: análisis de cuellos de botella, riesgos de micro-cortes y alternativas técnicas reales en la arquitectura actual.

---

## 1) Resumen ejecutivo

La app está funcional y ya tiene mejoras de resiliencia, pero **todavía hay puntos estructurales** que pueden provocar cortes intermitentes o degradación en conexiones persistentes (mensajería, notificaciones, sesiones idle):

1. **Arquitectura con doble salto local** (TUN -> VLESS local 127.0.0.1 -> proxy local -> canal remoto) agrega complejidad y puntos de fallo.
2. **Modelo por conexión con hilos no acotados** en el proxy (`thread` por cliente + dos hilos por relay) puede saturar CPU/RAM en picos.
3. **Canales túnel “pegados”**: si un lado queda semi-colgado, algunos flujos pueden quedar en espera demasiado tiempo.
4. **Reconexión orientada a nuevos flujos**, no a continuidad de sesiones viejas cuando hay micro-corte de red.
5. **Descubrimiento/resolución repetitiva** (DNS + fallback por intento) en rutas críticas puede sumar latencia y jitter.

Conclusión práctica: el mayor salto de robustez vendrá de **reducir capas (si es posible), limitar concurrencia de hilos, mejorar vida de sesiones idle y health-check activo**.

---

## 2) Flujo actual (simplificado)

1. `MainActivity` configura perfil/MUX/streams y arranca `LocalVpnService`.
2. `LocalVpnService` levanta:
   - Proxy local (`BlackTunnelClient.startProxy`) en `0.0.0.0:10809`.
   - `libbox` con inbound TUN y outbound VLESS a `127.0.0.1:10809`.
3. Por cada conexión local al proxy, se abre/negocia canal remoto con fallback IPv6/IPv4.
4. Relay bidireccional por sockets con contadores de tráfico.

---

## 3) Cuellos de botella / riesgos reales observables

### A) Exceso de hilos por conexión (riesgo alto en carga)

- Se crea un hilo por cliente aceptado y dos hilos por relay bidireccional.
- En escenarios con muchas conexiones concurrentes (apps modernas + background), esto escala mal.

**Impacto:** GC/picos CPU, latencia variable, potencial retraso en envío/ACK y micro-cortes percibidos.

**Señal de campo:** cuando hay varias apps activas (mensajería + navegación + descargas), aumenta variabilidad.

---

### B) Canal remoto por flujo/cliente, no canal persistente compartido

- Cada cliente del proxy abre su negociación/canal remoto en vez de reutilizar una sesión troncal estable.

**Impacto:** más handshakes, más puntos de fallo transitorio, más probabilidad de perder una conexión idle.

---

### C) Relays bloqueantes y salida tardía en sockets semi-colgados

- Unidireccionales con `read()` bloqueante y sincronización por `CountDownLatch`.
- Si una dirección queda a media caída, se puede tardar en limpiar recursos.

**Impacto:** conexiones “zombies”, recursos ocupados, degradación progresiva.

---

### D) Reintentos/fallback agresivos pero costosos

- Hay buena resiliencia (IPv6 estático, IPv6 dinámico, IPv4 fallback, payloads variantes), pero repetir resolución/intentos por flujo cuesta.

**Impacto:** jitter al abrir nuevos flujos en mala red; consumo extra cuando la red está inestable.

---

### E) Modo Gamer per-app: correcto en idea, pero requiere UX de “sin app seleccionada” muy explícita

- Si no hay app gamer seleccionada, el túnel gamer queda en modo espera (sin tráfico útil esperado).

**Impacto:** usuario puede interpretarlo como “conectado pero no funciona”.

---

## 4) Bloqueantes potenciales para conexiones persistentes (idle)

1. **Ausencia de keepalive de aplicación explícito por sesión lógica** (más allá de socket keepalive del sistema).
2. **No hay watchdog de salud del canal con reconexión preventiva** para sesiones de baja actividad.
3. **No hay backpressure unificado** (cola/pool) para limitar explosión de trabajo en picos.

---

## 5) Recomendaciones técnicas priorizadas

## Prioridad alta (rápido impacto)

1. **Pool de hilos / coroutines con límite** en vez de crear hilos sin tope por cliente/relay.
2. **Timeouts de inactividad + limpieza proactiva** en relays (idle timeout configurable por perfil).
3. **Health-check periódico liviano del canal** (ping/control frame) y reconexión rápida si estado degradado.
4. **Cache DNS/endpoint con TTL corto** para no resolver en caliente cada flujo.

## Prioridad media (arquitectura)

5. **Canal troncal persistente reutilizable** para múltiples flujos lógicos (cuando el protocolo lo permita).
6. **Métricas internas más finas**: handshake_ms, reconnect_count, active_tunnels, drops_idle, queue_depth.
7. **Histeresis de reconexión** (evitar serrucho connect/disconnect ante pérdidas breves).

## Prioridad estratégica (rediseño)

8. **Evaluar quitar el proxy local y enrutar más directo en libbox/sing-box** para reducir una capa socket y un punto de fallo.

---

## 6) Sugerencias avanzadas para tu compilación de sing-box/libbox

> Enfocadas en configuración/runtime, no cambios profundos de núcleo.

1. **Reducir “saltos” si puedes**:
   - Si tu backend/protocolo lo permite, considerar pipeline más directo desde TUN al outbound real en sing-box, sin proxy local intermedio.

2. **Keepalive/timeout tuning**:
   - Ajustar keepalive efectivo para sesiones idle.
   - Revisar tiempos de cierre/half-open para detectar conexiones muertas antes.

3. **Mejor multiplexado por perfil**:
   - `normal`: conservador-agresivo estable (lo que ya probaste: `smux + streams altos`).
   - `battery`: menor paralelismo + timeouts más estrictos.
   - `ultra`: alto paralelismo + buffers más amplios + watchdog activo.

4. **Observabilidad**:
   - Exponer contadores de reintentos, fallos handshake, latencia de apertura y resets por ruta (IPv6 estático/dinámico/IPv4).
   - Con eso vas a saber si el problema está en DNS, handshake, relay o keepalive.

5. **Rutas preferidas con memoria de éxito**:
   - Ya hay feedback IPv6: extenderlo a puntuación por estabilidad (no solo último éxito).

---

## 7) Perfilado sugerido para validar hipótesis

1. Prueba 30-60 min con mensajería idle + notificaciones + descarga paralela.
2. Registrar cada 5s:
   - conexiones activas,
   - uso CPU,
   - latencia de apertura de nuevo flujo,
   - reintentos de canal,
   - tasa de reconexión.
3. Comparar:
   - actual vs pool de hilos,
   - actual vs canal troncal reutilizable,
   - actual vs ruta más directa (si implementas).

---

## 8) Quick wins para próxima iteración

1. Meter límite de concurrencia en proxy/relay.
2. Añadir watchdog de canal cada N segundos.
3. Añadir contador de “stalls” > X segundos en relay.
4. Mensaje UX claro en Gamer cuando no hay app seleccionada.
5. Exponer en UI 2-3 métricas de salud (no solo ↓/↑): estado canal, reconexiones, uptime de sesión.

---

## 9) Cierre

La base está mejor que antes, pero los micro-cortes residuales probablemente provienen de **combinación de arquitectura por capas + concurrencia no acotada + salud de sesiones idle**.  
Para máxima estabilidad real: **menos capas, más control de concurrencia y más telemetría de salud del canal**.
