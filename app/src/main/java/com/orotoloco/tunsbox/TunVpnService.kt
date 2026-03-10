package com.orotoloco.tunsbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class TunVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var singBoxProcess: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startAsForeground("Inicializando VPN…")
                connectSafe()
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    private fun connectSafe() {
        try {
            connect()
        } catch (t: Throwable) {
            Log.e(TAG, "Error al iniciar VPN/sing-box", t)
            disconnect()
        }
    }

    private fun connect() {
        if (tunInterface != null) return

        val configFile = copyAsset("singbox-config.json")
        val localBinary = ensureBinaryForCurrentAbi() ?: run {
            Log.e(TAG, "No se pudo preparar binario sing-box desde assets")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        tunInterface = Builder()
            .setSession("TunSingboxVless")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .establish()

        if (tunInterface == null) {
            Log.e(TAG, "No se pudo establecer interfaz TUN")
            disconnect()
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

        Thread {
            try {
                singBoxProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { Log.i(TAG, "[sing-box] $it") }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error leyendo logs de sing-box", t)
            }
        }.start()

        updateForeground("VPN conectada/iniciando motor")
        Log.i(TAG, "sing-box arrancado con ${localBinary.absolutePath}")
    }

    private fun ensureBinaryForCurrentAbi(): File? {
        val target = File(filesDir, "sing-box")
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" }
        if (abi == null) {
            Log.e(TAG, "Solo se empaquetó binario arm64-v8a en esta base")
            return null
        }

        val assetPath = "sing-box/arm64-v8a/sing-box"
        return try {
            assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.setExecutable(true)
            target
        } catch (t: Throwable) {
            Log.e(TAG, "No existe asset requerido: $assetPath", t)
            null
        }
    }

    private fun disconnect() {
        singBoxProcess?.destroy()
        singBoxProcess = null
        tunInterface?.close()
        tunInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
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

    private fun startAsForeground(text: String) {
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TunSingboxVless")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_vpn_ic)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN service",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TunVpnService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 10011
        const val ACTION_CONNECT = "com.orotoloco.tunsbox.CONNECT"
        const val ACTION_DISCONNECT = "com.orotoloco.tunsbox.DISCONNECT"
    }
}
