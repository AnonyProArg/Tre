package com.orotoloco.tunsbox

import android.content.Intent
import android.net.VpnService
import android.os.Build
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
        val localBinary = ensureBinaryForCurrentAbi()

        tunInterface = Builder()
            .setSession("TunSingboxVless")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .establish()

        if (!localBinary.exists()) {
            Log.e(TAG, "No se encontró binario sing-box para ABI del dispositivo")
            return
        }

        singBoxProcess = ProcessBuilder(
            localBinary.absolutePath,
            "run",
            "-c",
            configFile.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        Log.i(TAG, "sing-box arrancado con ${localBinary.absolutePath}")
    }

    private fun ensureBinaryForCurrentAbi(): File {
        val target = File(filesDir, "sing-box")
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" }
        if (abi == null) {
            Log.e(TAG, "Solo se empaquetó binario arm64-v8a en esta base")
            return target
        }

        val assetPath = "sing-box/arm64-v8a/sing-box"
        assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        target.setExecutable(true)
        return target
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
