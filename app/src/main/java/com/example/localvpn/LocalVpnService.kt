package com.example.localvpn

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (running.get()) return START_STICKY

        val builder = Builder()
            .setSession("LocalVpnProxySession")
            .setMtu(1500)
            .addAddress("10.10.0.2", 24)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)

        excludeTermuxFromTun(builder)

        vpnInterface = builder.establish() ?: return START_NOT_STICKY

        running.set(true)
        forwardingThread = Thread {
            forwardPacketsToLocalProxy()
        }.also { it.start() }

        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        forwardingThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (_: IOException) {
        }
        super.onDestroy()
    }


    private fun excludeTermuxFromTun(builder: Builder) {
        try {
            builder.addDisallowedApplication(TERMUX_PACKAGE)
        } catch (_: PackageManager.NameNotFoundException) {
            // Termux no está instalado; no se requiere exclusión.
        }
    }

    private fun forwardPacketsToLocalProxy() {
        val tunFd = vpnInterface ?: return
        val input = FileInputStream(tunFd.fileDescriptor)
        val packetBuffer = ByteArray(MAX_PACKET_SIZE)

        while (running.get()) {
            try {
                val packetSize = input.read(packetBuffer)
                if (packetSize <= 0) continue

                sendToProxy(packetBuffer, packetSize)
            } catch (_: IOException) {
                running.set(false)
            }
        }
    }

    private fun sendToProxy(packet: ByteArray, length: Int) {
        Socket().use { socket ->
            protect(socket)
            socket.connect(InetSocketAddress(PROXY_HOST, PROXY_PORT), PROXY_TIMEOUT_MS)
            val out = socket.getOutputStream()
            out.write(packet, 0, length)
            out.flush()
        }
    }

    companion object {
        private const val MAX_PACKET_SIZE = 32767
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = 1080
        private const val PROXY_TIMEOUT_MS = 2500
        private const val TERMUX_PACKAGE = "com.termux"
    }
}
