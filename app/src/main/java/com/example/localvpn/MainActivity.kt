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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var hwidLabel: TextView
    private lateinit var accountLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var serverStateLabel: TextView
    private lateinit var tunStackSpinner: Spinner
    private lateinit var smuxStreamsInput: EditText
    private lateinit var toggleVpnButton: Button
    private lateinit var batteryButton: Button
    private lateinit var shareNetButton: Button

    private var hwid: String = ""
    private var lastAuthOk = false
    private var lastAuthDomain = ""
    private var isVpnConnected = false
    private var shareNetEnabled = false
    private var serverList: List<AppSettings.SavedServer> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hwidLabel = findViewById(R.id.hwidLabel)
        accountLabel = findViewById(R.id.accountLabel)
        statusLabel = findViewById(R.id.statusLabel)
        serverSpinner = findViewById(R.id.serverSpinner)
        serverStateLabel = findViewById(R.id.serverStateLabel)
        tunStackSpinner = findViewById(R.id.tunStackSpinner)
        smuxStreamsInput = findViewById(R.id.smuxStreamsInput)
        toggleVpnButton = findViewById(R.id.startVpnButton)
        batteryButton = findViewById(R.id.batteryButton)
        shareNetButton = findViewById(R.id.shareProxyButton)

        val stackValues = listOf("gvisor", "system", "mixed")
        val stackAdapter = ArrayAdapter(this, R.layout.spinner_item_selected, stackValues)
        stackAdapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
        tunStackSpinner.adapter = stackAdapter

        hwid = BlackTunnelClient.getOrCreateHwid(noBackupFilesDir)
        hwidLabel.text = "HWID: $hwid"

        val savedStack = AppSettings.getTunStack(this)
        tunStackSpinner.setSelection(stackValues.indexOf(savedStack).coerceAtLeast(0))
        smuxStreamsInput.setText(AppSettings.getSmuxMaxStreams(this).toString())

        accountLabel.text = AppSettings.getAccountSummary(this).ifBlank { getString(R.string.account_unknown) }
        loadServersFromStorage()
        refreshPersistentConnectionState()
        refreshBatteryButtonVisibility()

        tunStackSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveConfigFromInputs(showToast = false)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        smuxStreamsInput.doAfterTextChanged {
            saveConfigFromInputs(showToast = false)
        }

        findViewById<Button>(R.id.copyIdButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hwid", hwid))
            Toast.makeText(this, getString(R.string.id_copied), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.updateServersButton).setOnClickListener {
            refreshServersFromCentral()
        }

        toggleVpnButton.setOnClickListener {
            if (isVpnConnected) stopVpnNow() else authenticateThenStart()
        }

        batteryButton.setOnClickListener { openBatteryOptimizationSettings() }
        findViewById<Button>(R.id.infoButton).setOnClickListener { showInfoDialog() }
        findViewById<Button>(R.id.telegramButton).setOnClickListener { openUrl("https://t.me/usernamematy") }
        findViewById<Button>(R.id.whatsappButton).setOnClickListener { openUrl("https://wa.me/543834636264") }

        shareNetButton.setOnClickListener {
            shareNetEnabled = !shareNetEnabled
            renderShareNetButton()
            if (shareNetEnabled) showShareNetInfoDialog()
        }
        renderShareNetButton()
    }

    override fun onPause() {
        saveConfigFromInputs(showToast = false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshPersistentConnectionState()
        refreshBatteryButtonVisibility()
    }

    private fun saveConfigFromInputs(showToast: Boolean): Boolean {
        val stack = tunStackSpinner.selectedItem?.toString().orEmpty()
        val smux = smuxStreamsInput.text.toString().toIntOrNull()

        if (smux == null) {
            if (showToast) Toast.makeText(this, "SMUX inválido", Toast.LENGTH_SHORT).show()
            return false
        }

        AppSettings.setTunStack(this, stack)
        AppSettings.setSmuxMaxStreams(this, smux)
        if (showToast) Toast.makeText(this, getString(R.string.config_saved), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun refreshServersFromCentral() {
        Toast.makeText(this, getString(R.string.servers_updating), Toast.LENGTH_SHORT).show()
        thread(name = "servers-refresh") {
            val servers = BlackTunnelClient.fetchServers()
            runOnUiThread {
                if (servers.isEmpty()) {
                    Toast.makeText(this, getString(R.string.servers_update_failed), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                val mapped = servers.map { AppSettings.SavedServer(it.host, it.region, it.status) }
                AppSettings.setServerList(this, mapped)
                val currentHost = AppSettings.getTunnelDomain(this)
                val selected = mapped.firstOrNull { it.host == currentHost }?.host ?: mapped.first().host
                AppSettings.setTunnelDomain(this, selected)
                loadServersFromStorage(selected)
                Toast.makeText(this, getString(R.string.servers_updated, mapped.size), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadServersFromStorage(preferredHost: String? = null) {
        serverList = AppSettings.getServerList(this)
        if (serverList.isEmpty()) {
            val emptyAdapter = ArrayAdapter(this, R.layout.spinner_item_selected, listOf(getString(R.string.no_servers)))
            emptyAdapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
            serverSpinner.adapter = emptyAdapter
            serverStateLabel.text = "⚪ ${getString(R.string.server_state_unknown)}"
            lastAuthDomain = ""
            return
        }

        val labels = serverList.mapIndexed { index, s -> "Server #${index + 1} | ${s.region.ifBlank { "N/A" }}" }
        val serverAdapter = ArrayAdapter(this, R.layout.spinner_item_selected, labels)
        serverAdapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
        serverSpinner.adapter = serverAdapter

        val hostToSelect = preferredHost ?: AppSettings.getTunnelDomain(this).ifBlank { serverList.first().host }
        val idx = serverList.indexOfFirst { it.host == hostToSelect }.coerceAtLeast(0)
        serverSpinner.setSelection(idx)
        onServerSelected(idx)

        serverSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                onServerSelected(position)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
    }

    private fun onServerSelected(position: Int) {
        val selected = serverList.getOrNull(position) ?: return
        AppSettings.setTunnelDomain(this, selected.host)
        lastAuthDomain = selected.host
        serverStateLabel.text = when (selected.status.lowercase()) {
            "online" -> "🟢 ${getString(R.string.server_online)}"
            "maintenance" -> "🟡 ${getString(R.string.server_maintenance)}"
            "offline" -> "🔴 ${getString(R.string.server_offline)}"
            else -> "⚪ ${getString(R.string.server_state_unknown)}"
        }
    }

    private fun authenticateThenStart() {
        if (!saveConfigFromInputs(showToast = false)) return

        val tunnelDomain = AppSettings.getTunnelDomain(this)
        if (tunnelDomain.isBlank()) {
            Toast.makeText(this, getString(R.string.need_update_servers), Toast.LENGTH_LONG).show()
            return
        }

        if (isExpiredLocally()) {
            Toast.makeText(this, getString(R.string.local_expired), Toast.LENGTH_LONG).show()
            stopVpnNow()
            return
        }

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

    private fun refreshBatteryButtonVisibility() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        batteryButton.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) android.view.View.GONE else android.view.View.VISIBLE
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
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.info_title))
            .setMessage(getString(R.string.info_text))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showShareNetInfoDialog() {
        val ip = getLocalIpv4Address().ifBlank { "192.168.43.1" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.share_proxy_title))
            .setMessage(getString(R.string.share_proxy_text, ip, BlackTunnelClient.LOCAL_PORT))
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                // se mantiene activado hasta que el usuario pulse otra vez el botón
            }
            .show()
    }

    private fun renderShareNetButton() {
        shareNetButton.text = if (shareNetEnabled) getString(R.string.share_net_on) else getString(R.string.share_net_off)
        shareNetButton.setBackgroundResource(if (shareNetEnabled) R.drawable.button_primary else R.drawable.button_ghost)
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
        return "Cuenta: ${info.name} | días: ${info.days} | expira: ${info.expire} | creado: ${info.created}" +
            if (info.premium) " | PREMIUM" else ""
    }

    private fun getLocalIpv4Address(): String {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching ""
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return@runCatching addr.hostAddress.orEmpty()
                    }
                }
            }
            ""
        }.getOrDefault("")
    }

    companion object {
        private const val REQUEST_CODE_PREPARE_VPN = 5001
    }
}
