package com.orotoloco.tunsbox

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class TunVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var singBoxProcess: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (tunInterface != null) return

        val configFile = copyAsset("singbox-config.json")
        validateConfigWithLibbox(configFile)

        tunInterface = Builder()
            .setSession("TunSingboxVless")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .establish()

        val binary = File(filesDir, "sing-box")
        if (!binary.exists()) {
            Log.e(TAG, "No se encontró filesDir/sing-box. Integra el motor oficial vía AAR + servicio completo.")
            return
        }

        singBoxProcess = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-c",
            configFile.absolutePath
        )
            .redirectErrorStream(true)
            .start()
    }

    private fun validateConfigWithLibbox(configFile: File) {
        if (!BuildConfig.HAS_LIBBOX_AAR) return
        try {
            val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
            val versionMethod = libboxClass.getMethod("version")
            val checkConfigMethod = libboxClass.getMethod("checkConfig", String::class.java)
            val version = versionMethod.invoke(null) as String
            checkConfigMethod.invoke(null, configFile.readText())
            Log.i(TAG, "libbox activo. versión=$version; config VLESS válida")
        } catch (t: Throwable) {
            Log.e(TAG, "libbox AAR presente, pero no se pudo validar config", t)
        }
    }

    private fun disconnect() {
        singBoxProcess?.destroy()
        singBoxProcess = null
        tunInterface?.close()
        tunInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun copyAsset(name: String): File {
        val target = File(filesDir, name)
        assets.open(name).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    companion object {
        private const val TAG = "TunVpnService"
        const val ACTION_CONNECT = "com.orotoloco.tunsbox.CONNECT"
        const val ACTION_DISCONNECT = "com.orotoloco.tunsbox.DISCONNECT"
    }
}
