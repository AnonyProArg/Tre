package com.example.localvpn

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var singBoxProcess: Process? = null
    private var stdoutThread: Thread? = null
    private var stderrThread: Thread? = null
    private var processWaitThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.get()) {
            emitLog("VPN service ya está activo")
            return START_STICKY
        }

        return try {
            emitLog("Iniciando VPN service")

            val builder = Builder()
                .setSession("TreSingBoxSession")
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

            val singBoxBinary = resolveSingBoxBinary()
            val configFile = writeSingBoxConfig()

            startSingBox(singBoxBinary, configFile)
            running.set(true)
            emitLog("VPN + sing-box en ejecución")
            START_STICKY
        } catch (e: Exception) {
            emitLog("ERROR iniciando VPN con sing-box: ${e.message}")
            Log.e(TAG, "Error iniciando VPN con sing-box", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        running.set(false)
        emitLog("Deteniendo servicio VPN")

        stdoutThread?.interrupt()
        stderrThread?.interrupt()
        processWaitThread?.interrupt()

        singBoxProcess?.destroy()
        singBoxProcess = null

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

    private fun startSingBox(binary: File, configFile: File) {
        val commands = buildSingBoxCommandVariants(binary, configFile)
        var lastFailure: String? = null

        commands.forEachIndexed { index, command ->
            emitLog("Intento ${index + 1}/${commands.size} ejecutando sing-box: ${command.joinToString(" ")}")

            try {
                val candidateProcess = ProcessBuilder(command)
                    .directory(filesDir)
                    .redirectErrorStream(false)
                    .start()

                Thread.sleep(PROCESS_BOOT_GRACE_MS)

                if (!candidateProcess.isAlive) {
                    val exitCode = candidateProcess.exitValue()
                    val stdout = candidateProcess.inputStream.bufferedReader().readText().trim()
                    val stderr = candidateProcess.errorStream.bufferedReader().readText().trim()

                    if (stdout.isNotBlank()) emitLog("SB-OUT(boot) | $stdout")
                    if (stderr.isNotBlank()) emitLog("SB-ERR(boot) | $stderr")

                    lastFailure = "salida temprana código=$exitCode"
                    emitLog("Intento ${index + 1} falló: $lastFailure")
                    return@forEachIndexed
                }

                singBoxProcess = candidateProcess
                attachProcessOutputReaders(candidateProcess)
                attachProcessWatcher(candidateProcess)
                emitLog("sing-box iniciado correctamente en intento ${index + 1}")
                return
            } catch (e: Exception) {
                lastFailure = e.message
                emitLog("Intento ${index + 1} lanzó excepción: ${e.message}")
            }
        }

        throw IOException("No se pudo iniciar sing-box con ninguna variante. Último error: $lastFailure")
    }

    private fun buildSingBoxCommandVariants(binary: File, configFile: File): List<List<String>> {
        val forcePassiveTunSupported = supportsRunFlag(binary, "--force-passive-tun")
        val variants = linkedSetOf<List<String>>()

        if (forcePassiveTunSupported) {
            variants += listOf(
                binary.absolutePath,
                "run",
                "-c",
                configFile.absolutePath,
                "--force-passive-tun"
            )
        }

        variants += listOf(binary.absolutePath, "run", "-c", configFile.absolutePath)
        variants += listOf(binary.absolutePath, "run", "--config", configFile.absolutePath)

        return variants.toList()
    }

    private fun supportsRunFlag(binary: File, flag: String): Boolean {
        return try {
            val helpProcess = ProcessBuilder(binary.absolutePath, "run", "-h")
                .directory(filesDir)
                .redirectErrorStream(true)
                .start()

            val completed = helpProcess.waitFor(HELP_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                helpProcess.destroyForcibly()
                emitLog("WARN: timeout leyendo ayuda de sing-box; se omite flag $flag")
                return false
            }

            val helpText = helpProcess.inputStream.bufferedReader().readText()
            val supported = helpText.contains(flag)
            emitLog("Compatibilidad de $flag: $supported")
            supported
        } catch (e: Exception) {
            emitLog("WARN: no se pudo inspeccionar flags de sing-box: ${e.message}")
            false
        }
    }

    private fun attachProcessOutputReaders(process: Process) {
        stdoutThread = Thread {
            process.inputStream.bufferedReader()?.useLines { lines ->
                lines.forEach { line -> emitLog("SB-OUT | $line") }
            }
        }.also { it.start() }

        stderrThread = Thread {
            process.errorStream.bufferedReader()?.useLines { lines ->
                lines.forEach { line -> emitLog("SB-ERR | $line") }
            }
        }.also { it.start() }
    }

    private fun attachProcessWatcher(process: Process) {
        processWaitThread = Thread {
            try {
                val exitCode = process.waitFor()
                if (running.get()) {
                    emitLog("sing-box finalizó con código: $exitCode")
                    running.set(false)
                    stopSelf()
                }
            } catch (_: InterruptedException) {
                // Servicio detenido explícitamente.
            }
        }.also { it.start() }
    }

    private fun resolveSingBoxBinary(): File {
        val nativeLibDir = applicationContext.applicationInfo.nativeLibraryDir
        val nativeBinary = File(nativeLibDir, SING_BOX_NATIVE_LIBRARY_NAME)

        emitLog("nativeLibraryDir runtime: $nativeLibDir")

        if (nativeBinary.exists()) {
            emitLog("Binario nativo detectado en: ${nativeBinary.absolutePath}")
            emitLog("Permiso de ejecución nativo: ${nativeBinary.canExecute()}")
            return nativeBinary
        }

        emitLog("WARN: no existe en nativeLibraryDir, intentando extraer desde APK")
        val extracted = extractNativeBinaryFromInstalledApk()
        emitLog("Binario extraído desde APK en: ${extracted.absolutePath}")
        emitLog("Permiso de ejecución extraído: ${extracted.canExecute()}")
        return extracted
    }

    private fun extractNativeBinaryFromInstalledApk(): File {
        val appInfo = applicationContext.applicationInfo
        val apkCandidates = buildList {
            add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.let { addAll(it) }
        }

        val targetDir = File(filesDir, "native-bin").apply { mkdirs() }
        val targetBinary = File(targetDir, "sing-box")

        apkCandidates.forEach { apkPath ->
            ZipFile(apkPath).use { zip ->
                APK_LIB_ENTRY_CANDIDATES.firstNotNullOfOrNull { entryPath ->
                    zip.getEntry(entryPath)?.let { entry ->
                        zip.getInputStream(entry).use { input ->
                            targetBinary.outputStream().use { output -> input.copyTo(output) }
                        }
                        targetBinary
                    }
                }?.let {
                    val executableApplied = it.setExecutable(true, false)
                    val readableApplied = it.setReadable(true, false)
                    emitLog(
                        "Permisos aplicados para fallback (x/r): $executableApplied/$readableApplied"
                    )
                    return it
                }
            }
        }

        throw IOException(
            "Binario nativo no encontrado. Buscado en nativeLibraryDir=${appInfo.nativeLibraryDir} " +
                "y entradas APK=${APK_LIB_ENTRY_CANDIDATES.joinToString()} " +
                "de ${apkCandidates.joinToString()}"
        )
    }

    private fun writeSingBoxConfig(): File {
        val configFile = File(filesDir, "config.json")
        configFile.writeText(SING_BOX_CONFIG_JSON)
        emitLog("Config escrita en: ${configFile.absolutePath}")
        return configFile
    }

    private fun emitLog(message: String) {
        Log.d(TAG, message)
        VpnLogStore.add(message)
    }

    companion object {
        private const val TAG = "LocalVpnService"
        private const val SING_BOX_NATIVE_LIBRARY_NAME = "libsingbox.so"
        private val APK_LIB_ENTRY_CANDIDATES = listOf(
            "lib/arm64-v8a/libsingbox.so",
            "lib/armeabi-v7a/libsingbox.so",
            "lib/x86_64/libsingbox.so",
            "lib/x86/libsingbox.so"
        )
        private const val TERMUX_PACKAGE_NAME = "com.termux"
        private const val HELP_COMMAND_TIMEOUT_SECONDS = 2L
        private const val PROCESS_BOOT_GRACE_MS = 1200L

        private const val SING_BOX_CONFIG_JSON = """
            {
              "log": {
                "level": "debug",
                "timestamp": true
              },
              "dns": {
                "servers": [
                  {
                    "tag": "google-dns",
                    "address": "https://8.8.8.8",
                    "detour": "proxy-out"
                  }
                ],
                "independent_cache": true
              },
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "interface_name": "tun0",
                  "address": ["172.19.0.1/30"],
                  "mtu": 1500,
                  "stack": "gvisor",
                  "sniff": true,
                  "sniff_override_destination": true
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
                "final": "proxy-out"
              }
            }
        """
    }
}
