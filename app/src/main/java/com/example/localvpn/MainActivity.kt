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
import androidx.appcompat.app.AlertDialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var hwidLabel: TextView
    private lateinit var accountLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var serverLabel: TextView
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
        serverLabel = findViewById(R.id.serverLabel)
        tunStackSpinner = findViewById(R.id.tunStackSpinner)
        smuxStreamsInput = findViewById(R.id.smuxStreamsInput)
        toggleVpnButton = findViewById(R.id.startVpnButton)

        val stackValues = listOf("system", "gvisor", "mixed")
        tunStackSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, stackValues)

        hwid = BlackTunnelClient.getOrCreateHwid(noBackupFilesDir)
        hwidLabel.text = "HWID: $hwid"

        val tunnelDomain = AppSettings.getTunnelDomain(this)
        serverLabel.text = getString(R.string.server_default)
        val savedStack = AppSettings.getTunStack(this)
        tunStackSpinner.setSelection(stackValues.indexOf(savedStack).coerceAtLeast(0))
        smuxStreamsInput.setText(AppSettings.getSmuxMaxStreams(this).toString())

        lastAuthDomain = tunnelDomain
        accountLabel.text = AppSettings.getAccountSummary(this).ifBlank { getString(R.string.account_unknown) }
        refreshPersistentConnectionState()

        findViewById<Button>(R.id.copyIdButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hwid", hwid))
            Toast.makeText(this, getString(R.string.id_copied), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.saveConfigButton).setOnClickListener {
            saveConfigFromInputs(showToast = true)
        }

        toggleVpnButton.setOnClickListener {
            if (isVpnConnected) stopVpnNow() else authenticateThenStart()
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener { openBatteryOptimizationSettings() }
        findViewById<Button>(R.id.infoButton).setOnClickListener { showInfoDialog() }
        findViewById<Button>(R.id.telegramButton).setOnClickListener { openUrl("https://t.me/usernamematy") }
        findViewById<Button>(R.id.whatsappButton).setOnClickListener { openUrl("https://wa.me/543834636264") }
        findViewById<Button>(R.id.shareProxyButton).setOnClickListener { showProxyInfo() }
    }

    override fun onResume() {
        super.onResume()
        refreshPersistentConnectionState()
    }

    private fun saveConfigFromInputs(showToast: Boolean): Boolean {
        val stack = tunStackSpinner.selectedItem?.toString().orEmpty()
        val smux = smuxStreamsInput.text.toString().toIntOrNull()

        if (smux == null) {
            Toast.makeText(this, "SMUX inválido", Toast.LENGTH_SHORT).show()
            return false
        }

        AppSettings.setTunStack(this, stack)
        AppSettings.setSmuxMaxStreams(this, smux)
        if (showToast) Toast.makeText(this, getString(R.string.config_saved), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun authenticateThenStart() {
        if (!saveConfigFromInputs(showToast = false)) return
        if (isExpiredLocally()) {
            Toast.makeText(this, getString(R.string.local_expired), Toast.LENGTH_LONG).show()
            stopVpnNow()
            return
        }

        val tunnelDomain = AppSettings.getTunnelDomain(this)
        updateUiState(verified = false, connected = false, status = getString(R.string.status_validating))

        thread(name = "auth-thread") {
            try {
                val info = BlackTunnelClient.auth(hwid, tunnelDomain)
                runOnUiThread {
                    val summary = formatAccountSummary(info)
                    accountLabel.text = summary
                    AppSettings.setAccountSummary(this, summary)
                    AppSettings.setAccountExpireEpochDay(this, parseExpireEpochDay(info.expire))
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
        AppSettings.setVpnActive(this, false)
        lastAuthOk = false
        updateUiState(verified = false, connected = false, status = getString(R.string.status_disconnected))
        Toast.makeText(this, getString(R.string.vpn_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun refreshPersistentConnectionState() {
        val active = LocalVpnService.isRunning(this)
        if (active) {
            lastAuthOk = true
            updateUiState(verified = true, connected = true, status = getString(R.string.status_connected))
        } else {
            updateUiState(verified = false, connected = false, status = getString(R.string.status_not_validated))
        }
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
                Toast.makeText(this, getString(R.string.need_auth), Toast.LENGTH_LONG).show()
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
            AppSettings.setVpnActive(this, true)
            updateUiState(verified = true, connected = true, status = getString(R.string.status_connected))
            Toast.makeText(this, getString(R.string.vpn_started), Toast.LENGTH_SHORT).show()
        } else {
            updateUiState(verified = false, connected = false, status = getString(R.string.status_disconnected))
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val pkg = packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                })
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showInfoDialog() {
        val message = getString(R.string.info_text)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.info_title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showProxyInfo() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.share_proxy_title))
            .setMessage(getString(R.string.share_proxy_text, BlackTunnelClient.LOCAL_PORT))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, getString(R.string.open_link_error), Toast.LENGTH_SHORT).show() }
    }

    private fun parseExpireEpochDay(raw: String): Long {
        val cleaned = raw.trim()
        if (cleaned.isBlank() || cleaned == "?") return -1L
        val patterns = listOf("yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy")
        patterns.forEach { pattern ->
            try {
                return LocalDate.parse(cleaned, DateTimeFormatter.ofPattern(pattern)).toEpochDay()
            } catch (_: DateTimeParseException) {
            }
        }
        return -1L
    }

    private fun isExpiredLocally(): Boolean {
        val expireDay = AppSettings.getAccountExpireEpochDay(this)
        if (expireDay < 0) return false
        return LocalDate.now().toEpochDay() > expireDay
    }

    private fun formatAccountSummary(info: BlackTunnelClient.AccountInfo): String {
        return "Cuenta: ${info.name} | días: ${info.days} | expira: ${info.expire}" +
            if (info.premium) " | PREMIUM" else ""
    }

    companion object {
        private const val REQUEST_CODE_PREPARE_VPN = 5001
    }
}
