package com.example.localvpn

import android.util.Log
import java.io.File
import java.net.Inet6Address
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
        try {
            socket?.close()
        } catch (_: Exception) {
        }

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
        val p1 = "GET / HTTP/1.1\r\nHost: $PROXY_HOST\r\n\r\n".toByteArray()
        val p2 = (
            "- / HTTP/1.1\r\n" +
                "Host: $tunnelDomain\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Action: $action\r\n" +
                "Auth: $hwid\r\n\r\n"
            ).toByteArray()

        return try {
            val socket = Socket()
            val address = InetSocketAddress(Inet6Address.getByName(PROXY_IPV6), PROXY_PORT)
            socket.connect(address, 10_000)
            protectSocket?.invoke(socket)
            socket.soTimeout = 400
            socket.getOutputStream().write(p1)
            socket.getOutputStream().write(p2)
            socket.getOutputStream().flush()

            val resp = readHttpHead(socket)
            if (!resp.contains("101")) {
                closeQuietly(socket)
                null to parseHeaders(resp)
            } else {
                socket to parseHeaders(resp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openChannel($action) falló", e)
            null to emptyMap()
        }
    }

    private fun readHttpHead(socket: Socket): String {
        val input = socket.getInputStream()
        val out = StringBuilder()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 5_000

        while (System.currentTimeMillis() < deadline) {
            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (n <= 0) break
            out.append(String(buf, 0, n))
            if (out.contains("\r\n\r\n")) break
        }

        return out.toString()
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val headers = linkedMapOf<String, String>()
        raw.lines().drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
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
