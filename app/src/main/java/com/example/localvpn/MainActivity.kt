package com.example.localvpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var hwidLabel: TextView
    private lateinit var accountLabel: TextView
    private lateinit var expireLabel: TextView
    private lateinit var daysLabel: TextView
    private lateinit var planLabel: TextView
    private lateinit var verificationStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var tunStackSpinner: Spinner

    private var hwid: String = ""
    private var lastAuthOk = false
    private var lastAuthDomain = ""

    private data class ServerOption(val label: String, val domain: String)

    private lateinit var serverOptions: List<ServerOption>
    private val stackOptions = listOf("system", "gvisor", "mixed")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hwidLabel = findViewById(R.id.hwidLabel)
        accountLabel = findViewById(R.id.accountLabel)
        expireLabel = findViewById(R.id.expireLabel)
        daysLabel = findViewById(R.id.daysLabel)
        planLabel = findViewById(R.id.planLabel)
        verificationStatus = findViewById(R.id.verificationStatus)
        connectionStatus = findViewById(R.id.connectionStatus)
        serverSpinner = findViewById(R.id.serverSpinner)
        tunStackSpinner = findViewById(R.id.tunStackSpinner)

        hwid = BlackTunnelClient.getOrCreateHwid(noBackupFilesDir)
        hwidLabel.text = "ID: $hwid"

        val savedDomain = AppSettings.getTunnelDomain(this)
        serverOptions = buildServerOptions(savedDomain)
        serverSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            serverOptions.map { it.label }
        )
        val serverIndex = serverOptions.indexOfFirst { it.domain.equals(savedDomain, ignoreCase = true) }
            .coerceAtLeast(0)
        serverSpinner.setSelection(serverIndex)

        tunStackSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            stackOptions
        )
        val savedStack = AppSettings.getTunStack(this)
        tunStackSpinner.setSelection(stackOptions.indexOf(savedStack).coerceAtLeast(0))

        findViewById<Button>(R.id.copyIdButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hwid", hwid))
            Toast.makeText(this, "ID copiado", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.startVpnButton).setOnClickListener { authenticateThenStart() }

        findViewById<Button>(R.id.stopVpnButton).setOnClickListener {
            startService(Intent(this, LocalVpnService::class.java).setAction(LocalVpnService.ACTION_STOP))
            stopService(Intent(this, LocalVpnService::class.java))
            lastAuthOk = false
            lastAuthDomain = ""
            verificationStatus.text = getString(R.string.status_unverified)
            connectionStatus.text = getString(R.string.status_disconnected)
            Toast.makeText(this, "VPN detenida", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            openBatteryOptimizationSettings()
        }
    }

    private fun buildServerOptions(savedDomain: String): List<ServerOption> {
        val options = mutableListOf(
            ServerOption("🇦🇷 Servidor 1", "2.brawlpass.com.ar")
        )
        if (savedDomain.isNotBlank() && options.none { it.domain.equals(savedDomain, ignoreCase = true) }) {
            options.add(ServerOption("🌐 Servidor guardado", savedDomain))
        }
        return options
    }

    private fun selectedDomain(): String {
        val idx = serverSpinner.selectedItemPosition.coerceAtLeast(0)
        return serverOptions.getOrNull(idx)?.domain ?: serverOptions.first().domain
    }

    private fun selectedTunStack(): String {
        val idx = tunStackSpinner.selectedItemPosition.coerceAtLeast(0)
        return stackOptions.getOrNull(idx) ?: "system"
    }

    private fun authenticateThenStart() {
        val tunnelDomain = selectedDomain()
        val tunStack = selectedTunStack()
        val smuxStreams = AppSettings.getSmuxMaxStreams(this)

        AppSettings.setTunnelDomain(this, tunnelDomain)
        AppSettings.setTunStack(this, tunStack)
        AppSettings.setSmuxMaxStreams(this, smuxStreams)

        connectionStatus.text = getString(R.string.status_connecting)
        Toast.makeText(this, "Verificando y conectando...", Toast.LENGTH_SHORT).show()

        thread(name = "auth-thread") {
            try {
                val info = BlackTunnelClient.auth(hwid, tunnelDomain)
                runOnUiThread {
                    accountLabel.text = "Cuenta: ${info.name}"
                    expireLabel.text = "Expira: ${info.expire}"
                    daysLabel.text = "Días restantes: ${info.days}"
                    planLabel.text = if (info.premium) "Plan: PREMIUM" else "Plan: ESTÁNDAR"
                    verificationStatus.text = getString(R.string.status_verified)
                    lastAuthOk = true
                    lastAuthDomain = tunnelDomain
                    requestVpnPermissionAndStart()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    lastAuthOk = false
                    accountLabel.text = "Cuenta: ${e.message}"
                    verificationStatus.text = getString(R.string.status_unverified)
                    connectionStatus.text = getString(R.string.status_disconnected)
                    Toast.makeText(this, "Auth falló: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestVpnPermissionAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, REQUEST_CODE_PREPARE_VPN)
        } else {
            onActivityResult(REQUEST_CODE_PREPARE_VPN, RESULT_OK, null)
        }
    }

    @Deprecated("Deprecated in Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_PREPARE_VPN) return

        if (resultCode == RESULT_OK) {
            if (!lastAuthOk) {
                Toast.makeText(this, "Debes autenticar antes de conectar", Toast.LENGTH_LONG).show()
                connectionStatus.text = getString(R.string.status_disconnected)
                return
            }

            val serviceIntent = Intent(this, LocalVpnService::class.java)
                .setAction(LocalVpnService.ACTION_START)
                .putExtra(LocalVpnService.EXTRA_HWID, hwid)
                .putExtra(LocalVpnService.EXTRA_TUNNEL_DOMAIN, lastAuthDomain)
                .putExtra(LocalVpnService.EXTRA_TUN_STACK, selectedTunStack())
                .putExtra(LocalVpnService.EXTRA_SMUX_MAX_STREAMS, AppSettings.getSmuxMaxStreams(this))

            startService(serviceIntent)
            connectionStatus.text = getString(R.string.status_connected)
            Toast.makeText(this, "VPN iniciada", Toast.LENGTH_SHORT).show()
        } else {
            connectionStatus.text = getString(R.string.status_disconnected)
            Toast.makeText(this, "Permiso de VPN denegado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val pkg = packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$pkg")
                startActivity(intent)
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    companion object {
        private const val REQUEST_CODE_PREPARE_VPN = 5001
    }
}
