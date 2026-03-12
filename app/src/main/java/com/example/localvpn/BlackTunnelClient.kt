package com.example.localvpn

import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object BlackTunnelClient {

    private const val PROXY_IPV6 = "2606:4700::6812:16b7"
    private const val PROXY_HOST = "emailmarketing.personal.com.ar"
    private const val PROXY_PORT = 80

    const val LOCAL_HOST = "127.0.0.1"
    const val LOCAL_PORT = 10800

    data class AccountInfo(
        val status: String,
        val name: String,
        val expire: String,
        val days: String,
        val premium: Boolean
    )

    class AuthException(message: String) : Exception(message)

    class ProxyHandle(
        private val stopFlag: AtomicBoolean,
        private val serverSocket: ServerSocket?,
        private val activeSockets: MutableSet<Socket>
    ) {
        fun stop() {
            stopFlag.set(true)
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

    fun auth(hwid: String, tunnelDomain: String): AccountInfo {
        val (socket, headers) = openChannel("auth", hwid, tunnelDomain, null)
        closeQuietly(socket)
        if (headers.isEmpty()) throw AuthException("Sin respuesta del servidor")

        val status = headers["x-status"] ?: "INVALID"

        return when (status) {
            "OK" -> AccountInfo(
                status = status,
                name = headers["x-name"] ?: hwid,
                expire = headers["x-expire"] ?: "?",
                days = headers["x-days-left"] ?: "?",
                premium = headers["x-premium"] == "1"
            )

            "EXPIRED" -> throw AuthException("Acceso expirado")
            else -> throw AuthException("HWID no autorizado ($status)")
        }
    }

    fun notifyDisconnect(hwid: String, tunnelDomain: String) {
        val (socket, _) = openChannel("disconnect", hwid, tunnelDomain, null)
        closeQuietly(socket)
    }

    fun startProxy(
        hwid: String,
        tunnelDomain: String,
        protectSocket: (Socket) -> Unit
    ): ProxyHandle {
        val stopFlag = AtomicBoolean(false)
        val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(LOCAL_HOST, LOCAL_PORT))
            soTimeout = 1_000
        }


        thread(name = "bt-proxy-accept", isDaemon = true) {
            while (!stopFlag.get()) {
                try {
                    val client = server.accept()
                    runCatching { protectSocket(client) }
                    activeSockets.add(client)
                    thread(name = "bt-proxy-client", isDaemon = true) {
                        handleClient(client, hwid, tunnelDomain, protectSocket)
                        activeSockets.remove(client)
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                }
            }
        }

        return ProxyHandle(stopFlag, server, activeSockets)
    }

    private fun handleClient(
        client: Socket,
        hwid: String,
        tunnelDomain: String,
        protectSocket: (Socket) -> Unit
    ) {
        val (tunnelSocket, headers) = openChannel("tunnel", hwid, tunnelDomain, protectSocket)
        if (tunnelSocket == null || headers["x-status"] != "OK") {
            closeQuietly(client)
            closeQuietly(tunnelSocket)
            return
        }


        thread(name = "bt-relay-up", isDaemon = true) { relay(client, tunnelSocket) }
        thread(name = "bt-relay-down", isDaemon = true) { relay(tunnelSocket, client) }
    }

    private fun relay(src: Socket, dst: Socket) {
        try {
            val buf = ByteArray(64 * 1024)
            val input = src.getInputStream()
            val output = dst.getOutputStream()
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                output.write(buf, 0, read)
            }
            output.flush()
        } catch (_: Exception) {
        } finally {
            runCatching { src.shutdownInput() }
            runCatching { dst.shutdownOutput() }
            closeQuietly(src)
            closeQuietly(dst)
        }
    }

    private fun openChannel(
        action: String,
        hwid: String,
        tunnelDomain: String,
        protectSocket: ((Socket) -> Unit)?
    ): Pair<Socket?, Map<String, String>> {
        val p1 = ("GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n").toByteArray()
        val p2 = (
            "- / HTTP/1.1\r\n" +
                "Host: $tunnelDomain\r\n" +
                "Upgrade: websocket\r\n" +
                "Action: $action\r\n" +
                "Auth: $hwid\r\n\r\n"
            ).toByteArray()

        connectAndSend(action, InetSocketAddress(Inet6Address.getByName(PROXY_IPV6), PROXY_PORT), p1, p2, protectSocket)
            .let { if (it.first != null) return it }

        val ipv6ByDns = runCatching { InetAddress.getAllByName(PROXY_HOST).filterIsInstance<Inet6Address>() }.getOrDefault(emptyList())
        ipv6ByDns.forEach { ip6 ->
            connectAndSend(action, InetSocketAddress(ip6, PROXY_PORT), p1, p2, protectSocket)
                .let { if (it.first != null) return it }
        }

        val ipv4Direct = runCatching { InetAddress.getAllByName(tunnelDomain).filterIsInstance<Inet4Address>() }.getOrDefault(emptyList())
        ipv4Direct.forEach { ip4 ->
            connectAndSend(action, InetSocketAddress(ip4, PROXY_PORT), null, p2, protectSocket)
                .let { if (it.first != null) return it }
        }

        return null to emptyMap()
    }

    private fun connectAndSend(
        action: String,
        address: InetSocketAddress,
        p1: ByteArray?,
        p2: ByteArray,
        protectSocket: ((Socket) -> Unit)?
    ): Pair<Socket?, Map<String, String>> {
        val socket = Socket()
        return try {
            protectSocket?.invoke(socket)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(address, 10_000)
            socket.soTimeout = 5_000
            val output = socket.getOutputStream()
            if (p1 != null) output.write(p1)
            output.write(p2)
            output.flush()
            val raw = readResponse(socket)
            val parsed = parseSecondResponse(raw)
            val headers = parsed.first
            if (headers["x-status"].isNullOrBlank()) {
                closeQuietly(socket)
                null to emptyMap()
            } else {
                if (action == "tunnel") socket.soTimeout = 0
                socket to headers
            }
        } catch (e: Exception) {
            closeQuietly(socket)
            null to emptyMap()
        }
    }

    private fun readResponse(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 7_000

        while (System.currentTimeMillis() < deadline && out.size() < 128 * 1024) {
            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (n <= 0) break
            out.write(buf, 0, n)
            val text = out.toString(Charsets.UTF_8.name())
            if ("\r\n\r\n" in text) break
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
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }
        return headers to parts.size
    }

    private fun closeQuietly(socket: Socket?) {
        runCatching { socket?.close() }
    }
}
