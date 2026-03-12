package com.example.localvpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

class LocalVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (commandServer != null) {
            emitLog("VPN ya está iniciada")
            return
        }

        try {
            emitLog("Iniciando VPN con libbox")

            val opts = SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = filesDir.absolutePath
                tempPath = cacheDir.absolutePath
                fixAndroidStack = true
                debug = true
            }
            Libbox.setup(opts)

            commandServer = CommandServer(this, this)
            commandServer?.start()

            val overrideOptions = OverrideOptions().apply {
                autoRedirect = true
            }
            commandServer?.startOrReloadService(DEFAULT_CONFIG, overrideOptions)

            emitLog("VPN iniciada correctamente")
        } catch (e: Exception) {
            emitLog("ERROR iniciando VPN: ${e.message}")
            Log.e(TAG, "Error iniciando VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        try {
            commandServer?.closeService()
            commandServer?.close()
            commandServer = null

            tunFd?.close()
            tunFd = null

            emitLog("VPN detenida")
            stopSelf()
        } catch (e: Exception) {
            emitLog("ERROR deteniendo VPN: ${e.message}")
            Log.e(TAG, "Error deteniendo VPN", e)
        }
    }

    override fun openTun(options: TunOptions): Int {
        emitLog("openTun llamado por libbox (mtu=${options.getMTU()})")

        val builder = Builder()
            .setSession("TreLibboxSession")
            .setMtu(options.getMTU())
            .setBlocking(false)

        val inet4 = options.getInet4Address()
        while (inet4.hasNext()) {
            val prefix = inet4.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        val inet6 = options.getInet6Address()
        while (inet6.hasNext()) {
            val prefix = inet6.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.getAutoRoute()) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }

        val excludePackages = options.getExcludePackage()
        while (excludePackages.hasNext()) {
            try {
                builder.addDisallowedApplication(excludePackages.next())
            } catch (_: Exception) {
                // ignorar paquete inválido/no instalado
            }
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
            // ignorar
        }

        tunFd?.close()
        tunFd = builder.establish() ?: throw IllegalStateException("No se pudo crear TUN")

        emitLog("TUN creado fd=${tunFd!!.fd}")
        return tunFd!!.detachFd()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner = ConnectionOwner()

    override fun getInterfaces(): NetworkInterfaceIterator? = null

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun systemCertificates(): StringIterator? = null

    override fun clearDNSCache() {}

    override fun sendNotification(notification: Notification) {}

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun getSystemProxyStatus(): SystemProxyStatus {
        val status = SystemProxyStatus()
        status.available = false
        status.enabled = false
        return status
    }

    override fun serviceReload() {
        emitLog("serviceReload recibido")
    }

    override fun serviceStop() {
        emitLog("serviceStop recibido")
        stopVpn()
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {}

    override fun writeDebugMessage(message: String) {
        emitLog("libbox: $message")
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun emitLog(message: String) {
        Log.i(TAG, message)
        VpnLogStore.add(message)
    }

    companion object {
        private const val TAG = "LocalVpnService"
        const val ACTION_START = "START_VPN"
        const val ACTION_STOP = "STOP_VPN"

        private val DEFAULT_CONFIG = """
            {
              "log": { "level": "debug", "timestamp": true },
              "dns": {
                "servers": [
                  { "type": "local", "tag": "local-dns" }
                ]
              },
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
                  "mtu": 1500,
                  "auto_route": true,
                  "strict_route": true,
                  "stack": "system"
                }
              ],
              "outbounds": [
                {
                  "type": "socks",
                  "tag": "proxy-out",
                  "server": "127.0.0.1",
                  "server_port": 1080
                }
              ],
              "route": {
                "rules": [
                  { "inbound": "tun-in", "outbound": "proxy-out" }
                ]
              }
            }
        """.trimIndent()
    }
}
