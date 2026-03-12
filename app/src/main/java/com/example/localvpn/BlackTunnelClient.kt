package com.example.localvpn

import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object BlackTunnelClient {

    private const val TAG = "BlackTunnelClient"

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
        private val serverSocket: ServerSocket?
    ) {
        fun stop() {
            stopFlag.set(true)
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun getOrCreateHwid(filesDir: File): String {
        val hwidFile = File(filesDir, "hwid.txt")
        if (hwidFile.exists()) {
            val value = hwidFile.readText().trim()
            if (value.isNotBlank()) return value
        }

        val hwid = UUID.randomUUID().toString()
        hwidFile.writeText(hwid)
        return hwid
    }

    fun auth(hwid: String, tunnelDomain: String): AccountInfo {
        val (socket, headers) = openChannel("auth", hwid, tunnelDomain)
        closeQuietly(socket)

        if (headers.isEmpty()) throw AuthException("Sin respuesta del servidor")

        return when (val status = headers["x-status"] ?: "INVALID") {
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

    fun startProxy(
        hwid: String,
        tunnelDomain: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ): ProxyHandle {
        val stopFlag = AtomicBoolean(false)
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(LOCAL_HOST, LOCAL_PORT))
            soTimeout = 1_000
        }

        logger("Proxy local en $LOCAL_HOST:$LOCAL_PORT")

        thread(name = "bt-proxy-accept", isDaemon = true) {
            while (!stopFlag.get()) {
                try {
                    val client = server.accept()
                    thread(name = "bt-proxy-client", isDaemon = true) {
                        handleClient(client, hwid, tunnelDomain, protectSocket, logger)
                    }
                } catch (_: SocketTimeoutException) {
                    // poll stop flag
                } catch (_: Exception) {
                    if (!stopFlag.get()) logger("WARN accept proxy falló")
                }
            }
        }

        return ProxyHandle(stopFlag, server)
    }

    private fun handleClient(
        client: Socket,
        hwid: String,
        tunnelDomain: String,
        protectSocket: (Socket) -> Unit,
        logger: (String) -> Unit
    ) {
        val (tunnelSocket, headers) = openChannel("tunnel", hwid, tunnelDomain, protectSocket)
        if (tunnelSocket == null || headers["x-status"] != "OK") {
            logger("WARN túnel rechazado: ${headers["x-status"] ?: "ERROR"}")
            closeQuietly(client)
            closeQuietly(tunnelSocket)
            return
        }

        logger("Túnel OK: ${headers["x-name"] ?: "?"} (${headers["x-days-left"] ?: "?"} días)")

        thread(name = "bt-relay-up", isDaemon = true) {
            relay(client, tunnelSocket)
        }
        thread(name = "bt-relay-down", isDaemon = true) {
            relay(tunnelSocket, client)
        }
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
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            closeQuietly(src)
            closeQuietly(dst)
        }
    }

    private fun openChannel(
        action: String,
        hwid: String,
        tunnelDomain: String,
        protectSocket: ((Socket) -> Unit)? = null
    ): Pair<Socket?, Map<String, String>> {
        val p1 = (
            "GET / HTTP/1.1\r\n" +
                "Host: $PROXY_HOST\r\n\r\n"
            ).toByteArray()

        val p2 = (
            "- / HTTP/1.1\r\n" +
                "Host: $tunnelDomain\r\n" +
                "Upgrade: websocket\r\n" +
                "Action: $action\r\n" +
                "Auth: $hwid\r\n\r\n"
            ).toByteArray()

        // 1) IPv6 hardcodeado
        connectAndSend(
            address = InetSocketAddress(Inet6Address.getByName(PROXY_IPV6), PROXY_PORT),
            p1 = p1,
            p2 = p2,
            protectSocket = protectSocket,
            mode = "ipv6-hard"
        ).let { if (it.first != null) return it }

        // 2) IPv6 via DNS del host señuelo
        val ipv6ByDns = runCatching {
            InetAddress.getAllByName(PROXY_HOST).filterIsInstance<Inet6Address>()
        }.getOrDefault(emptyList())

        ipv6ByDns.forEach { ip6 ->
            connectAndSend(
                address = InetSocketAddress(ip6, PROXY_PORT),
                p1 = p1,
                p2 = p2,
                protectSocket = protectSocket,
                mode = "ipv6-dns:${ip6.hostAddress}"
            ).let { if (it.first != null) return it }
        }

        // 3) IPv4 directo al servidor real (sin p1)
        val ipv4Direct = runCatching {
            InetAddress.getAllByName(tunnelDomain).filterIsInstance<Inet4Address>()
        }.getOrDefault(emptyList())

        ipv4Direct.forEach { ip4 ->
            connectAndSend(
                address = InetSocketAddress(ip4, PROXY_PORT),
                p1 = null,
                p2 = p2,
                protectSocket = protectSocket,
                mode = "ipv4-direct:${ip4.hostAddress}"
            ).let { if (it.first != null) return it }
        }

        Log.w(TAG, "openChannel($action): todos los intentos fallaron")
        return null to emptyMap()
    }

    private fun connectAndSend(
        address: InetSocketAddress,
        p1: ByteArray?,
        p2: ByteArray,
        protectSocket: ((Socket) -> Unit)?,
        mode: String
    ): Pair<Socket?, Map<String, String>> {
        val socket = Socket()
        return try {
            // En Android es clave excluir este socket del TUN para evitar loop.
            protectSocket?.invoke(socket)
            socket.connect(address, 10_000)
            socket.soTimeout = 500

            val output = socket.getOutputStream()
            if (p1 != null) output.write(p1)
            output.write(p2)
            output.flush()

            val raw = readResponse(socket)
            val headers = parseSecondResponse(raw)
            if (headers["x-status"].isNullOrBlank()) {
                closeQuietly(socket)
                null to emptyMap()
            } else {
                Log.d(TAG, "openChannel OK ($mode), status=${headers["x-status"]}")
                socket to headers
            }
        } catch (e: Exception) {
            closeQuietly(socket)
            Log.d(TAG, "connectAndSend fallo ($mode): ${e.message}")
            null to emptyMap()
        }
    }

    private fun readResponse(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        val data = ArrayList<Byte>()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 5_000

        while (System.currentTimeMillis() < deadline) {
            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (n <= 0) break
            repeat(n) { i -> data.add(buf[i]) }

            if (String(buf, 0, n).contains("\r\n\r\n")) {
                Thread.sleep(50)
                val extra = try {
                    input.read(buf)
                } catch (_: Exception) {
                    -1
                }
                if (extra > 0) repeat(extra) { i -> data.add(buf[i]) }
                break
            }
        }

        return data.toByteArray()
    }

    private fun parseSecondResponse(raw: ByteArray): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val text = raw.toString(Charsets.UTF_8)
        val parts = text.split("\r\n\r\n")

        val target = parts.firstOrNull { it.contains("HTTP/1.1 101") || it.contains(" 101 ") }
            ?: parts.firstOrNull()
            ?: return emptyMap()

        val headers = linkedMapOf<String, String>()
        target.split("\r\n").forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
