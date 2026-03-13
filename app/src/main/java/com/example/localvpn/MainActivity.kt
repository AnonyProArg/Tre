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
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var hwidLabel: TextView
    private lateinit var accountLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var tunnelDomainInput: EditText
    private lateinit var tunStackSpinner: Spinner
    private lateinit var smuxStreamsInput: EditText
    private lateinit var toggleVpnButton: Button

    private var hwid: String = ""
    private var lastAuthOk = false
    private var lastAuthDomain = ""
    private var isVpnConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hwidLabel = findViewById(R.id.hwidLabel)
        accountLabel = findViewById(R.id.accountLabel)
        statusLabel = findViewById(R.id.statusLabel)
        tunnelDomainInput = findViewById(R.id.tunnelDomainInput)
        tunStackSpinner = findViewById(R.id.tunStackSpinner)
        smuxStreamsInput = findViewById(R.id.smuxStreamsInput)
        toggleVpnButton = findViewById(R.id.startVpnButton)

        val stackValues = listOf("system", "gvisor", "mixed")
        tunStackSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, stackValues)

        hwid = BlackTunnelClient.getOrCreateHwid(noBackupFilesDir)
        hwidLabel.text = "HWID: $hwid"
        tunnelDomainInput.setText(AppSettings.getTunnelDomain(this))
        val savedStack = AppSettings.getTunStack(this)
        tunStackSpinner.setSelection(stackValues.indexOf(savedStack).coerceAtLeast(0))
        smuxStreamsInput.setText(AppSettings.getSmuxMaxStreams(this).toString())

        updateUiState(verified = false, connected = false, status = getString(R.string.status_not_validated))

        findViewById<Button>(R.id.saveDomainButton).setOnClickListener {
            saveConfigFromInputs(showToast = true)
        }

        findViewById<Button>(R.id.copyIdButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hwid", hwid))
            Toast.makeText(this, getString(R.string.id_copied), Toast.LENGTH_SHORT).show()
        }

        toggleVpnButton.setOnClickListener {
            if (isVpnConnected) {
                stopVpnNow()
            } else {
                authenticateThenStart()
            }
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            openBatteryOptimizationSettings()
        }
    }

    private fun saveConfigFromInputs(showToast: Boolean): Boolean {
        val domain = tunnelDomainInput.text.toString().trim()
        val stack = tunStackSpinner.selectedItem?.toString().orEmpty()
        val smux = smuxStreamsInput.text.toString().toIntOrNull()

        if (domain.isBlank()) {
            Toast.makeText(this, "Dominio inválido", Toast.LENGTH_SHORT).show()
            return false
        }
        if (smux == null) {
            Toast.makeText(this, "SMUX inválido", Toast.LENGTH_SHORT).show()
            return false
        }

        AppSettings.setTunnelDomain(this, domain)
        AppSettings.setTunStack(this, stack)
        AppSettings.setSmuxMaxStreams(this, smux)
        if (showToast) Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun authenticateThenStart() {
        if (!saveConfigFromInputs(showToast = false)) return

        val tunnelDomain = tunnelDomainInput.text.toString().trim()

        updateUiState(verified = false, connected = false, status = getString(R.string.status_validating))

        thread(name = "auth-thread") {
            try {
                val info = BlackTunnelClient.auth(hwid, tunnelDomain)
                runOnUiThread {
                    accountLabel.text = "Cuenta: ${info.name} | días: ${info.days} | expira: ${info.expire}" +
                        if (info.premium) " | PREMIUM" else ""
                    lastAuthOk = true
                    lastAuthDomain = tunnelDomain
                    updateUiState(verified = true, connected = false, status = getString(R.string.status_connecting))
                    requestVpnPermissionAndStart()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    lastAuthOk = false
                    updateUiState(verified = false, connected = false, status = getString(R.string.status_error))
                    accountLabel.text = "Cuenta: ${e.message}"
                    Toast.makeText(this, "Auth falló: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopVpnNow() {
        startService(Intent(this, LocalVpnService::class.java).setAction(LocalVpnService.ACTION_STOP))
        stopService(Intent(this, LocalVpnService::class.java))
        lastAuthOk = false
        lastAuthDomain = ""
        updateUiState(verified = false, connected = false, status = getString(R.string.status_disconnected))
        Toast.makeText(this, "VPN detenida", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState(verified: Boolean, connected: Boolean, status: String) {
        isVpnConnected = connected
        toggleVpnButton.text = if (connected) getString(R.string.stop_vpn) else getString(R.string.start_vpn)
        val prefix = if (verified) "🟢" else "⚪"
        statusLabel.text = "$prefix $status"
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
                updateUiState(verified = false, connected = false, status = getString(R.string.status_not_validated))
                return
            }

            val serviceIntent = Intent(this, LocalVpnService::class.java)
                .setAction(LocalVpnService.ACTION_START)
                .putExtra(LocalVpnService.EXTRA_HWID, hwid)
                .putExtra(LocalVpnService.EXTRA_TUNNEL_DOMAIN, lastAuthDomain)
                .putExtra(LocalVpnService.EXTRA_TUN_STACK, AppSettings.getTunStack(this))
                .putExtra(LocalVpnService.EXTRA_SMUX_MAX_STREAMS, AppSettings.getSmuxMaxStreams(this))

            startService(serviceIntent)
            updateUiState(verified = true, connected = true, status = getString(R.string.status_connected))
            Toast.makeText(this, "VPN iniciada", Toast.LENGTH_SHORT).show()
        } else {
            updateUiState(verified = false, connected = false, status = getString(R.string.status_disconnected))
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
