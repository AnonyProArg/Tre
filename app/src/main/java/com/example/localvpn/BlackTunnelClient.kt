package com.example.localvpn

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object BlackTunnelClient {

    private const val PROXY_IPV6 = "2606:4700::6812:16b7"
    private const val PROXY_HOST = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT = 80
    private const val CENTRAL_HOST = "central.brawlpass.com.ar"

    private const val CLOUDFRONT_DECOY_HOST = "recarga.personal.com.ar"
    private const val CLOUDFRONT_FAKE_HOST = "d36wp69rjuikpvh.cloudfront.net"
    private const val CDN_CLOUDFRONT = "cloudfront"
    private const val CDN_CLOUDFLARE = "cloudflare"

    const val LOCAL_HOST = "0.0.0.0"
    const val LOCAL_PORT = 10809

    data class AccountInfo(
        val status: String,
        val name: String,
        val expire: String,
        val days: String,
        val premium: Boolean,
        val created: String
    )

    data class ServerInfo(val host: String, val region: String, val status: String)

    data class TrafficSnapshot(val uplinkBytes: Long, val downlinkBytes: Long)


    class AuthException(message: String) : Exception(message)

    class ProxyHandle(
        private val stopFlag: AtomicBoolean,
        private val serverSocket: ServerSocket?,
        private val activeSockets: MutableSet<Socket>
    ) {
        fun stop() {
            stopFlag.set(true)
            resetTrafficCounters()
            runCatching { serverSocket?.close() }
            synchronized(activeSockets) {
                activeSockets.forEach { runCatching { it.close() } }
                activeSockets.clear()
            }
        }
    }

    fun getOrCreateHwid(baseDir: File, logger: ((String) -> Unit)? = null): String {
        val targetDir = File(baseDir, "no_backup_hwid").apply { mkdirs() }
        val hwidFile = File(targetDir, "hwid.txt")
        if (hwidFile.exists()) {
            val value = hwidFile.readText().trim()
            if (value.isNotBlank()) {
                logger?.invoke("HWID cargado desde ${hwidFile.absolutePath}")
                return value
            }
        }
        val hwid = UUID.randomUUID().toString()
        hwidFile.writeText(hwid)
        logger?.invoke("HWID generado nuevo en ${hwidFile.absolutePath}")
        return hwid
    }

    fun fetchServers(cdnMode: String, logger: ((String) -> Unit)? = null): List<ServerInfo> {
        val request = (
            "BT-SERVERS / HTTP/1.1\r\n" +
                "Host: ${centralHostForCdn(cdnMode)}\r\n" +
                "Upgrade: websocket\r\n\r\n"
            ).toByteArray()

        repeat(CHANNEL_CONNECT_RETRIES) { attempt ->
            // 1) Método principal: IPv6 fija + variantes de payload.
            buildPayloadVariants(cdnMode).forEach { payload ->
                val response = connectForServers(
                    address = InetSocketAddress(Inet6Address.getByName(PROXY_IPV6), PROXY_PORT),
                    p1 = payload,
                    request = request,
                    cdnMode = cdnMode,
                    logger = logger
                )
                if (response.isNotEmpty()) return response
            }

            // 2) Método secundario: IPv6 dinámica por feedback + DNS.
            resolveDynamicIpv6Candidates(centralHostForCdn(cdnMode), cdnMode).forEach { ip6 ->
                buildPayloadVariants(cdnMode).forEach { payload ->
                    val response = connectForServers(
                        address = InetSocketAddress(ip6, PROXY_PORT),
                        p1 = payload,
                        request = request,
                        cdnMode = cdnMode,
                        logger = logger
                    )
                    if (response.isNotEmpty()) return response
                }
            }

            // 3) Último recurso: IPv4 directa al central (suele resetear, pero queda como fallback).
            val ipv4 = runCatching { InetAddress.getAllByName(centralHostForCdn(cdnMode)).filterIsInstance<Inet4Address>() }
                .getOrDefault(emptyList())
            ipv4.forEach { ip4 ->
                val response = connectForServers(
                    address = InetSocketAddress(ip4, 80),
                    p1 = null,
                    request = request,
                    cdnMode = cdnMode,
                    logger = logger
                )
                if (response.isNotEmpty()) return response
            }

            if (attempt < CHANNEL_CONNECT_RETRIES - 1) {
                logger?.invoke("Reintentando actualización de servidores (${attempt + 1}/$CHANNEL_CONNECT_RETRIES)")
                Thread.sleep((350L * (attempt + 1)).coerceAtMost(1_000L))
            }
        }

        return emptyList()
    }

    private fun connectForServers(
        address: InetSocketAddress,
        p1: ByteArray?,
        request: ByteArray,
        cdnMode: String,
        logger: ((String) -> Unit)?
    ): List<ServerInfo> {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(address, 12_000)
            socket.soTimeout = 9_000
            socket.receiveBufferSize = 256 * 1024
            socket.sendBufferSize = 256 * 1024
            val out = socket.getOutputStream()
            if (p1 != null) out.write(p1)
            out.write(request)
            out.flush()
            val raw = readResponse(socket)
            val headers = parseSecondResponse(raw).first
            val xServers = headers["x-servers"].orEmpty()
            val parsed = if (xServers.isBlank()) emptyList() else parseServerList(xServers)
            if (parsed.isNotEmpty() && socket.inetAddress is Inet6Address) {
                synchronized(ipv6FeedbackLock) {
                    lastGoodResolve = CachedIpv6Resolve(socket.inetAddress.hostAddress.orEmpty(), centralHostForCdn(cdnMode), normalizeCdnMode(cdnMode), System.currentTimeMillis())
                }
            }
            parsed
        } catch (e: Exception) {
            logger?.invoke("fetchServers connect error=${e.message}")
            emptyList()
        } finally {
            closeQuietly(socket)
        }
    }

    private fun parseServerList(value: String): List<ServerInfo> {
        val result = linkedMapOf<String, ServerInfo>()
        value.split(',').forEach { token ->
            val item = token.trim()
            val open = item.indexOf('(')
            val close = item.lastIndexOf(')')
            if (open <= 0 || close <= open) return@forEach
            val host = item.substring(0, open).trim().lowercase()
            val inside = item.substring(open + 1, close)
            val parts = inside.split('|')
            val region = parts.getOrNull(0)?.trim().orEmpty()
            val status = parts.getOrNull(1)?.trim()?.lowercase().orEmpty()
            if (host.isNotBlank()) result[host] = ServerInfo(host, region, status)
        }
        return result.values.toList()
    }

    fun auth(hwid: String, tunnelDomain: String, cdnMode: String, logger: ((String) -> Unit)? = null): AccountInfo {
        logger?.invoke("AUTH start dominio=$tunnelDomain hwid=$hwid")
        val (socket, headers) = openChannel("auth", hwid, tunnelDomain, cdnMode, null, logger)
        closeQuietly(socket)
        if (headers.isEmpty()) throw AuthException("Sin respuesta del servidor")

        val status = headers["x-status"] ?: "INVALID"
        return when (status) {
            "OK" -> AccountInfo(
                status = status,
                name = headers["x-name"] ?: hwid,
                expire = headers["x-expire"] ?: "?",
                days = headers["x-days-left"] ?: "?",
                premium = headers["x-premium"] == "1",
                created = headers["x-created"] ?: "?"
            )
            "EXPIRED" -> throw AuthException("Acceso expirado")
            else -> throw AuthException("HWID no autorizado ($status)")
        }
    }

    fun notifyDisconnect(hwid: String, tunnelDomain: String, cdnMode: String, logger: ((String) -> Unit)? = null) {
        val (socket, _) = openChannel("disconnect", hwid, tunnelDomain, cdnMode, null, logger)
        closeQuietly(socket)
    }

    fun startProxy(
        hwid: String,
        tunnelDomain: String,
        protectSocket: (Socket) -> Unit,
        cdnMode: String,
        logger: (String) -> Unit
    ): ProxyHandle {
        resetTrafficCounters()
        val stopFlag = AtomicBoolean(false)
        val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(LOCAL_HOST, LOCAL_PORT))
            soTimeout = 1_000
        }

        logger("Proxy local en $LOCAL_HOST:$LOCAL_PORT dominio=$tunnelDomain")

        thread(name = "bt-proxy-accept", isDaemon = true) {
            while (!stopFlag.get()) {
                try {
                    val client = server.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        receiveBufferSize = 256 * 1024
                        sendBufferSize = 256 * 1024
                    }
                    runCatching { protectSocket(client) }
                    activeSockets.add(client)
                    thread(name = "bt-proxy-client", isDaemon = true) {
                        handleClient(client, hwid, tunnelDomain, cdnMode, protectSocket, logger)
                        activeSockets.remove(client)
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!stopFlag.get()) logger("WARN accept proxy falló")
                }
            }
        }

        return ProxyHandle(stopFlag, server, activeSockets)
    }

    private fun handleClient(
        client: Socket,
        hwid: String,
        tunnelDomain: String,
        cdnMode: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        val (tunnelSocket, headers) = openChannel("tunnel", hwid, tunnelDomain, cdnMode, protectSocket, logger)
        if (tunnelSocket == null || headers["x-status"] != "OK") {
            logger("WARN canal túnel rechazado o sin respuesta")
            closeQuietly(client)
            closeQuietly(tunnelSocket)
            return
        }

        val done = CountDownLatch(2)
        thread(name = "bt-relay-up", isDaemon = true) {
            relayOneWay(client, tunnelSocket, isUplink = true)
            done.countDown()
        }
        thread(name = "bt-relay-down", isDaemon = true) {
            relayOneWay(tunnelSocket, client, isUplink = false)
            done.countDown()
        }

        done.await()
        closeQuietly(client)
        closeQuietly(tunnelSocket)
    }

    private fun relayOneWay(src: Socket, dst: Socket, isUplink: Boolean) {
        try {
            val buf = ByteArray(64 * 1024)
            val input = src.getInputStream()
            val output = dst.getOutputStream()
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                output.write(buf, 0, read)
                if (isUplink) uplinkBytes.addAndGet(read.toLong()) else downlinkBytes.addAndGet(read.toLong())
            }
            output.flush()
        } catch (_: Exception) {
        } finally {
            runCatching { src.shutdownInput() }
            runCatching { dst.shutdownOutput() }
        }
    }

    private fun openChannel(
        action: String,
        hwid: String,
        tunnelDomain: String,
        cdnMode: String,
        protectSocket: ((Socket) -> Unit)?,
        logger: ((String) -> Unit)?
    ): Pair<Socket?, Map<String, String>> {
        val p2 = buildActionPayload(action, hwid, tunnelDomain, cdnMode)

        // Método principal: IPv6 estática con payload principal/secundario.
        repeat(CHANNEL_CONNECT_RETRIES) { attempt ->
            buildPayloadVariants(cdnMode).forEach { payload ->
                connectAndSend(
                    address = InetSocketAddress(Inet6Address.getByName(PROXY_IPV6), PROXY_PORT),
                    p1 = payload,
                    p2 = p2,
                    protectSocket = protectSocket,
                    logger = logger
                ).let { if (it.first != null) return rememberSuccessfulEndpoint(it, tunnelDomain, cdnMode, logger) }
            }

            // Método secundario: IPv6 dinámicas por feedback (cache) + DNS del host señuelo/túnel.
            resolveDynamicIpv6Candidates(tunnelDomain, cdnMode).forEach { ip6 ->
                buildPayloadVariants(cdnMode).forEach { payload ->
                    connectAndSend(
                        address = InetSocketAddress(ip6, PROXY_PORT),
                        p1 = payload,
                        p2 = p2,
                        protectSocket = protectSocket,
                        logger = logger
                    ).let { if (it.first != null) return rememberSuccessfulEndpoint(it, tunnelDomain, cdnMode, logger) }
                }
            }

            // Tercer intento: IPv4 directo al dominio del túnel (sin payload señuelo p1).
            val ipv4Direct = runCatching {
                InetAddress.getAllByName(tunnelDomain).filterIsInstance<Inet4Address>()
            }.getOrDefault(emptyList())
            ipv4Direct.forEach { ip4 ->
                connectAndSend(
                    address = InetSocketAddress(ip4, PROXY_PORT),
                    p1 = null,
                    p2 = p2,
                    protectSocket = protectSocket,
                    logger = logger
                ).let { if (it.first != null) return rememberSuccessfulEndpoint(it, tunnelDomain, cdnMode, logger) }
            }

            if (attempt < CHANNEL_CONNECT_RETRIES - 1) {
                logger?.invoke("Reintentando canal $action (${attempt + 1}/$CHANNEL_CONNECT_RETRIES)")
                Thread.sleep((400L * (attempt + 1)).coerceAtMost(1_200L))
            }
        }

        return null to emptyMap()
    }

    private fun connectAndSend(
        address: InetSocketAddress,
        p1: ByteArray?,
        p2: ByteArray,
        protectSocket: ((Socket) -> Unit)?,
        logger: ((String) -> Unit)?
    ): Pair<Socket?, Map<String, String>> {
        val socket = Socket()
        return try {
            protectSocket?.invoke(socket)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(address, 15_000)
            socket.soTimeout = 12_000
            socket.receiveBufferSize = 256 * 1024
            socket.sendBufferSize = 256 * 1024
            val output = socket.getOutputStream()
            if (p1 != null) output.write(p1)
            output.write(p2)
            output.flush()
            val raw = readResponse(socket)
            val headers = parseSecondResponse(raw).first
            if (headers["x-status"].isNullOrBlank()) {
                closeQuietly(socket)
                null to emptyMap()
            } else {
                if (String(p2, Charsets.UTF_8).contains("Action: tunnel")) socket.soTimeout = 0
                socket to headers
            }
        } catch (e: Exception) {
            closeQuietly(socket)
            logger?.invoke("connectAndSend error=${e.message}")
            null to emptyMap()
        }
    }

    private fun readResponse(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val n = try { input.read(buf) } catch (_: SocketTimeoutException) { break }
            if (n <= 0) break
            out.write(buf, 0, n)
            if (String(buf, 0, n).contains("\r\n\r\n")) {
                Thread.sleep(40)
                val extra = try { input.read(buf) } catch (_: Exception) { -1 }
                if (extra > 0) out.write(buf, 0, extra)
                break
            }
            if (out.size() > 128 * 1024) break
        }
        return out.toByteArray()
    }

    private fun parseSecondResponse(raw: ByteArray): Pair<Map<String, String>, Int> {
        if (raw.isEmpty()) return emptyMap<String, String>() to 0
        val text = raw.toString(Charsets.UTF_8)
        val parts = text.split("\r\n\r\n")
        val target = parts.firstOrNull { it.contains("HTTP/1.1 101") } ?: parts.firstOrNull().orEmpty()
        if (target.isBlank()) return emptyMap<String, String>() to parts.size
        val headers = linkedMapOf<String, String>()
        target.split("\r\n").forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        return headers to parts.size
    }

    private fun buildPayloadVariants(cdnMode: String): List<ByteArray?> {
        val mode = normalizeCdnMode(cdnMode)
        return if (mode == CDN_CLOUDFRONT) {
            listOf(("HEAD / HTTP/1.1\r\nHost: $CLOUDFRONT_DECOY_HOST\r\n\r\n").toByteArray())
        } else {
            val primary = ("GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n").toByteArray()
            val secondary = ("HEAD / HTTP/1.1\r\nHost: $PROXY_HOST\r\nConnection: keep-alive\r\n\r\n").toByteArray()
            listOf(primary, secondary)
        }
    }

    private fun resolveDynamicIpv6Candidates(tunnelDomain: String, cdnMode: String): List<Inet6Address> {
        val ordered = LinkedHashSet<String>()
        synchronized(ipv6FeedbackLock) {
            if (lastGoodResolve.ipv6Literal.isNotBlank() && (lastGoodResolve.domain.isBlank() || lastGoodResolve.domain == tunnelDomain) && (lastGoodResolve.mode.isBlank() || lastGoodResolve.mode == cdnMode) && System.currentTimeMillis() - lastGoodResolve.savedAtMs <= IPV6_CACHE_TTL_MS) {
                ordered += lastGoodResolve.ipv6Literal
            }
        }

        val decoyHost = if (normalizeCdnMode(cdnMode) == CDN_CLOUDFRONT) CLOUDFRONT_DECOY_HOST else PROXY_HOST

        runCatching { InetAddress.getAllByName(decoyHost).filterIsInstance<Inet6Address>() }
            .getOrDefault(emptyList())
            .forEach { ordered += it.hostAddress.orEmpty() }

        runCatching { InetAddress.getAllByName(tunnelDomain).filterIsInstance<Inet6Address>() }
            .getOrDefault(emptyList())
            .forEach { ordered += it.hostAddress.orEmpty() }

        return ordered.mapNotNull { literal ->
            runCatching { Inet6Address.getByName(literal) as? Inet6Address }.getOrNull()
        }
    }

    private fun rememberSuccessfulEndpoint(
        result: Pair<Socket?, Map<String, String>>,
        tunnelDomain: String,
        cdnMode: String,
        logger: ((String) -> Unit)?
    ): Pair<Socket?, Map<String, String>> {
        val socket = result.first ?: return result
        val remote = socket.inetAddress
        if (remote is Inet6Address) {
            synchronized(ipv6FeedbackLock) {
                lastGoodResolve = CachedIpv6Resolve(
                    ipv6Literal = remote.hostAddress.orEmpty(),
                    domain = tunnelDomain,
                    mode = normalizeCdnMode(cdnMode),
                    savedAtMs = System.currentTimeMillis()
                )
            }
            logger?.invoke("Canal OK via IPv6 dinámica: ${remote.hostAddress}")
        }
        return result
    }


    private fun normalizeCdnMode(value: String): String {
        return when (value.trim().lowercase()) {
            CDN_CLOUDFRONT, CDN_CLOUDFLARE -> value.trim().lowercase()
            else -> CDN_CLOUDFRONT
        }
    }

    private fun centralHostForCdn(cdnMode: String): String {
        return CENTRAL_HOST
    }

    private fun buildActionPayload(action: String, hwid: String, tunnelDomain: String, cdnMode: String): ByteArray {
        val mode = normalizeCdnMode(cdnMode)
        return if (mode == CDN_CLOUDFRONT) {
            (
                "- / HTTP/1.1\r\n" +
                    "Host: $CLOUDFRONT_DECOY_HOST\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Action: $action\r\n" +
                    "Auth: $hwid\r\n" +
                    "Tunnel-Url: $tunnelDomain\r\n" +
                    "Tunnel-Host: $CLOUDFRONT_FAKE_HOST\r\n" +
                    "Tunnel-Cdn: $mode\r\n\r\n"
                ).toByteArray()
        } else {
            (
                "- / HTTP/1.1\r\n" +
                    "Host: $tunnelDomain\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Action: $action\r\n" +
                    "Auth: $hwid\r\n" +
                    "Tunnel-Cdn: $mode\r\n\r\n"
                ).toByteArray()
        }
    }

    fun getTrafficSnapshot(): TrafficSnapshot {
        return TrafficSnapshot(
            uplinkBytes = uplinkBytes.get(),
            downlinkBytes = downlinkBytes.get()
        )
    }

    private fun resetTrafficCounters() {
        uplinkBytes.set(0)
        downlinkBytes.set(0)
    }

    private fun closeQuietly(socket: Socket?) {
        runCatching { socket?.close() }
    }

    private const val CHANNEL_CONNECT_RETRIES = 3
    private val uplinkBytes = AtomicLong(0)
    private val downlinkBytes = AtomicLong(0)
    private val ipv6FeedbackLock = Any()
    private data class CachedIpv6Resolve(
        val ipv6Literal: String,
        val domain: String,
        val mode: String,
        val savedAtMs: Long
    )
    private var lastGoodResolve = CachedIpv6Resolve("", "", "", 0L)
    private const val IPV6_CACHE_TTL_MS = 2 * 60 * 1000L
}
