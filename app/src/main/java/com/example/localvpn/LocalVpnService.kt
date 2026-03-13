package com.example.localvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
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
import java.util.concurrent.atomic.AtomicBoolean

class LocalVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var proxyHandle: BlackTunnelClient.ProxyHandle? = null
    private var libboxServiceStarted = false
    private var lastStartIntent: Intent? = null
    private var isStopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            else -> {
                lastStartIntent = intent
                startVpn(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(intent: Intent?) {
        if (commandServer != null) {
            emitLog("VPN ya está iniciada")
            return
        }

        try {
            isStopping = false
            startForegroundCompat()
            emitLog("Iniciando VPN con libbox")

            val hwid = intentHwidOrLocal()
            val tunnelDomain = intentDomainOrSettings()
            val tunStack = intentTunStackOrSettings()
            val smuxMaxStreams = intentSmuxOrSettings()

            emitLog("HWID sesión: $hwid")
            emitLog("Dominio túnel sesión: $tunnelDomain")
            emitLog("TUN stack sesión: $tunStack")
            emitLog("SMUX max_streams sesión: $smuxMaxStreams")

            proxyHandle = BlackTunnelClient.startProxy(
                hwid = hwid,
                tunnelDomain = tunnelDomain,
                protectSocket = { socket -> protect(socket) },
                logger = {}
            )

            setupLibboxOnce()

            commandServer = CommandServer(this, this)
            val overrideOptions = OverrideOptions()
            disableClashIfPresent(overrideOptions)
            commandServer?.startOrReloadService(buildClientConfigJson(tunnelDomain, tunStack, smuxMaxStreams), overrideOptions)
            attachInterfaceProtectorIfAvailable(commandServer)
            libboxServiceStarted = true

            AppSettings.setVpnActive(this, true)
            emitLog("VPN iniciada correctamente")
        } catch (e: Exception) {
            emitLog("ERROR iniciando VPN: ${e.message}")
            Log.e(TAG, "Error iniciando VPN", e)
            stopVpn()
        }
    }

    private fun intentHwidOrLocal(): String {
        val fromIntent = lastStartIntent?.getStringExtra(EXTRA_HWID)?.trim().orEmpty()
        if (fromIntent.isNotBlank()) return fromIntent
        emitLog("WARN EXTRA_HWID ausente, usando HWID local")
        return BlackTunnelClient.getOrCreateHwid(noBackupFilesDir)
    }

    private fun intentDomainOrSettings(): String {
        val fromIntent = lastStartIntent?.getStringExtra(EXTRA_TUNNEL_DOMAIN)?.trim().orEmpty()
        if (fromIntent.isNotBlank()) return fromIntent
        emitLog("WARN EXTRA_TUNNEL_DOMAIN ausente, usando ajuste guardado")
        return AppSettings.getTunnelDomain(this)
    }

    private fun intentTunStackOrSettings(): String {
        val fromIntent = lastStartIntent?.getStringExtra(EXTRA_TUN_STACK)?.trim().orEmpty()
        if (fromIntent.isNotBlank()) return fromIntent
        emitLog("WARN EXTRA_TUN_STACK ausente, usando ajuste guardado")
        return AppSettings.getTunStack(this)
    }

    private fun intentSmuxOrSettings(): Int {
        val fromIntent = lastStartIntent?.getIntExtra(EXTRA_SMUX_MAX_STREAMS, -1) ?: -1
        if (fromIntent > 0) return fromIntent
        emitLog("WARN EXTRA_SMUX_MAX_STREAMS ausente, usando ajuste guardado")
        return AppSettings.getSmuxMaxStreams(this)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_MIN)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("VPN activa")
            .setContentText("BlackTunnel en segundo plano")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun attachInterfaceProtectorIfAvailable(server: Any?) {
        if (server == null) return
        try {
            val method = server.javaClass.methods.firstOrNull {
                it.name.contains("InterfaceProtector", ignoreCase = true) && it.parameterCount == 1
            } ?: return
            val protectorType = method.parameterTypes[0]
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                protectorType.classLoader,
                arrayOf(protectorType)
            ) { _, called, args ->
                if (called.name.contains("protect", ignoreCase = true) && args?.isNotEmpty() == true) {
                    val fd = (args[0] as? Number)?.toInt() ?: -1
                    if (fd >= 0) {
                        val ok = protect(fd)
                        return@newProxyInstance if (called.returnType == java.lang.Boolean.TYPE) ok else null
                    }
                }
                if (called.returnType == java.lang.Boolean.TYPE) false else null
            }
            method.invoke(server, proxy)
            emitLog("InterfaceProtector enlazado")
        } catch (e: Exception) {
            emitLog("WARN InterfaceProtector no disponible: ${e.message}")
        }
    }

    private fun setupLibboxOnce() {
        if (isLibboxSetupDone.get()) return
        synchronized(libboxSetupLock) {
            if (isLibboxSetupDone.get()) return
            val opts = SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = filesDir.absolutePath
                tempPath = cacheDir.absolutePath
                fixAndroidStack = true
                debug = false
            }
            Libbox.setup(opts)
            isLibboxSetupDone.set(true)
            emitLog("Libbox.setup() aplicado")
        }
    }

    private fun stopVpn() {
        if (isStopping) return
        isStopping = true

        var stopError: Exception? = null

        try {
            if (libboxServiceStarted) {
                commandServer?.closeService()
            }
        } catch (e: Exception) {
            stopError = e
        }

        try {
            commandServer?.close()
        } catch (e: Exception) {
            if (stopError == null) stopError = e
        } finally {
            commandServer = null
        }

        try {
            val hwid = lastStartIntent?.getStringExtra(EXTRA_HWID)?.trim().orEmpty()
            val domain = lastStartIntent?.getStringExtra(EXTRA_TUNNEL_DOMAIN)?.trim().orEmpty()
            if (hwid.isNotBlank() && domain.isNotBlank()) {
                BlackTunnelClient.notifyDisconnect(hwid, domain)
            }
            proxyHandle?.stop()
        } catch (e: Exception) {
            if (stopError == null) stopError = e
        } finally {
            proxyHandle = null
        }

        try {
            tunFd?.close()
        } catch (e: Exception) {
            if (stopError == null) stopError = e
        } finally {
            tunFd = null
        }

        libboxServiceStarted = false
        lastStartIntent = null
        AppSettings.setVpnActive(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (stopError != null) {
            emitLog("WARN deteniendo VPN: ${stopError.message}")
            Log.w(TAG, "Error deteniendo VPN", stopError)
        } else {
            emitLog("VPN detenida")
        }

        stopSelf()
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
            }
        }

        try {
            builder.addDisallowedApplication(APP_PACKAGE_NAME)
            emitLog("App excluida del TUN: $APP_PACKAGE_NAME")
        } catch (e: Exception) {
            emitLog("WARN no se pudo excluir app del TUN: ${e.message}")
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

    override fun writeDebugMessage(message: String) {}

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun disableClashIfPresent(overrideOptions: OverrideOptions) {
        try {
            val clazz = overrideOptions.javaClass
            clazz.methods
                .filter { it.name.contains("clash", ignoreCase = true) && it.parameterCount == 1 }
                .forEach { method ->
                    val type = method.parameterTypes[0]
                    when (type) {
                        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> method.invoke(overrideOptions, false)
                        String::class.java -> method.invoke(overrideOptions, "")
                    }
                }

            clazz.fields
                .filter { it.name.contains("clash", ignoreCase = true) }
                .forEach { field ->
                    when (field.type) {
                        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> field.set(overrideOptions, false)
                        String::class.java -> field.set(overrideOptions, "")
                    }
                }
        } catch (e: Exception) {
            emitLog("WARN no se pudo ajustar overrideOptions (clash): ${e.message}")
        }
    }

    private fun buildClientConfigJson(tunnelDomain: String, tunStack: String, smuxMaxStreams: Int): String {
        return """
            {
              "log": { "level": "error", "timestamp": false },
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
                  "auto_route": true,
                  "strict_route": true,
                  "sniff": true,
                  "stack": "${tunStack}",
                  "exclude_package": ["${APP_PACKAGE_NAME}"]
                }
              ],
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "proxy",
                  "server": "127.0.0.1",
                  "server_port": 10800,
                  "uuid": "11111111-1111-1111-1111-111111111111",
                  "flow": "",
                  "multiplex": {
                    "enabled": true,
                    "protocol": "smux",
                    "max_streams": ${smuxMaxStreams}
                  },
                  "packet_encoding": "xudp",
                  "network_strategy": "default"
                },
                { "type": "direct", "tag": "direct" },
                { "type": "block",  "tag": "block" }
              ],
              "route": {
                "rules": [
                  { "action": "sniff" },
                  { "protocol": "dns", "action": "hijack-dns" },
                  { "ip_is_private": true, "outbound": "direct" },
                  {
                    "domain": ["${tunnelDomain}", "emailmarketing.personal.com.ar"],
                    "outbound": "direct"
                  },
                  {
                    "ip_cidr": ["2606:4700::6812:16b7/128", "127.0.0.1/32"],
                    "outbound": "direct"
                  }
                ],
                "auto_detect_interface": true,
                "default_interface": "wlan0",
                "final": "proxy"
              }
            }
        """.trimIndent()
    }

    private fun emitLog(message: String) {
        VpnLogStore.add(message)
    }

    companion object {
        private const val TAG = "LocalVpnService"
        private const val APP_PACKAGE_NAME = "com.example.localvpn"
        const val ACTION_START = "START_VPN"
        const val ACTION_STOP = "STOP_VPN"
        const val EXTRA_HWID = "extra_hwid"
        const val EXTRA_TUNNEL_DOMAIN = "extra_tunnel_domain"
        const val EXTRA_TUN_STACK = "extra_tun_stack"
        const val EXTRA_SMUX_MAX_STREAMS = "extra_smux_max_streams"
        private const val NOTIF_CHANNEL_ID = "vpn_foreground"
        private const val NOTIF_ID = 1001
        private val libboxSetupLock = Any()
        private val isLibboxSetupDone = AtomicBoolean(false)

        fun isRunning(context: android.content.Context): Boolean = AppSettings.isVpnActive(context)
    }
}
