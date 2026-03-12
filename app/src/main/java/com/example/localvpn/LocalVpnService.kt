package com.example.localvpn

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var libboxRuntime: LibboxRuntime? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.get()) {
            emitLog("VPN service ya está activo")
            return START_STICKY
        }

        return try {
            emitLog("Iniciando VPN service")

            val builder = Builder()
                .setSession("TreLibboxSession")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")

            excludeTermuxFromVpn(builder)

            vpnInterface = builder.establish() ?: run {
                emitLog("ERROR: no se pudo crear la interfaz TUN")
                return START_NOT_STICKY
            }
            emitLog("Interfaz TUN creada correctamente")

            val libboxConfig = writeLibboxConfig()
            val libbox = LibboxRuntime(::emitLog)
            val libboxStarted = libbox.tryStart(this, vpnInterface!!, libboxConfig.readText())

            if (!libboxStarted) {
                emitLog("ERROR: libbox no pudo iniciar")
                stopSelf()
                return START_NOT_STICKY
            }

            libboxRuntime = libbox
            running.set(true)
            emitLog("VPN + libbox en ejecución")
            START_STICKY
        } catch (e: Exception) {
            emitLog("ERROR iniciando VPN con libbox: ${e.message}")
            Log.e(TAG, "Error iniciando VPN con libbox", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        running.set(false)
        emitLog("Deteniendo servicio VPN")

        libboxRuntime?.stop()
        libboxRuntime = null

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            emitLog("WARN cerrando VPN: ${e.message}")
            Log.w(TAG, "Error cerrando interfaz VPN", e)
        }
        vpnInterface = null

        emitLog("Servicio VPN detenido")
        super.onDestroy()
    }

    private fun excludeTermuxFromVpn(builder: Builder) {
        try {
            builder.addDisallowedApplication(TERMUX_PACKAGE_NAME)
            emitLog("Termux excluido del túnel: $TERMUX_PACKAGE_NAME")
        } catch (e: PackageManager.NameNotFoundException) {
            emitLog("Termux no instalado; sin exclusión")
            Log.w(TAG, "Termux no instalado; no se excluye de la VPN", e)
        }
    }

    private fun writeLibboxConfig(): File {
        val configFile = File(filesDir, "config-libbox.json")
        configFile.writeText(buildLibboxConfigJson())
        emitLog("Config libbox escrita en: ${configFile.absolutePath}")
        return configFile
    }

    private fun buildLibboxConfigJson(): String {
        return """
            {
              "log": {
                "level": "debug",
                "timestamp": true
              },
              "dns": {
                "servers": [
                  {
                    "type": "local",
                    "tag": "local"
                  }
                ],
                "independent_cache": true
              },
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "auto_route": true,
                  "strict_route": true,
                  "mtu": 1500,
                  "stack": "gvisor",
                  "sniff": true,
                  "sniff_override_destination": true,
                  "address": ["172.19.0.1/30"]
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
                  {
                    "protocol": "dns",
                    "action": "hijack-dns"
                  },
                  {
                    "inbound": "tun-in",
                    "action": "route",
                    "outbound": "proxy-out"
                  }
                ],
                "final": "proxy-out",
                "default_domain_resolver": "local"
              }
            }
        """.trimIndent()
    }

    private fun emitLog(message: String) {
        Log.i(TAG, message)
        VpnLogStore.add(message)
    }

    companion object {
        private const val TAG = "LocalVpnService"
        private const val TERMUX_PACKAGE_NAME = "com.termux"
    }
}
