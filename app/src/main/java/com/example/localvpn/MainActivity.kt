package com.example.localvpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.LinearLayout
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Spinner
import android.widget.TextView
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var hwidLabel: TextView
    private lateinit var accountLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var trafficLabel: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var serverStateLabel: TextView
    private lateinit var tunStackSpinner: Spinner
    private lateinit var profileSpinner: Spinner
    private lateinit var muxProtocolSpinner: Spinner
    private lateinit var muxStreamsSpinner: Spinner
    private lateinit var customStreamsInput: EditText
    private lateinit var gamerSection: LinearLayout
    private lateinit var gamerSearchInput: EditText
    private lateinit var gamerAppsList: ListView
    private lateinit var gamerManualPackageInput: EditText
    private lateinit var gamerAddPackageButton: Button
    private lateinit var customProxySection: LinearLayout
    private lateinit var customProxyHostInput: EditText
    private lateinit var customProxyPortInput: EditText
    private lateinit var customPayload1Input: EditText
    private lateinit var customPayload2Input: EditText
    private lateinit var toggleVpnButton: Button
    private lateinit var batteryButton: Button
    private lateinit var shareNetButton: Button

    private var hwid: String = ""
    private var lastAuthOk = false
    private var lastAuthDomain = ""
    private var isVpnConnected = false
    private var shareNetEnabled = false
    private var serverList: List<AppSettings.SavedServer> = emptyList()
    private val trafficUpdateHandler = Handler(Looper.getMainLooper())
    private var lastTrafficUp = 0L
    private var lastTrafficDown = 0L
    private var lastTrafficTs = 0L
    private var lastToggleAtMs = 0L
    private var allLaunchableApps: List<Pair<String, String>> = emptyList()
    private var filteredLaunchableApps: List<Pair<String, String>> = emptyList()
    private lateinit var gamerAppsAdapter: ArrayAdapter<String>
    private var selectedGamerPackages: MutableSet<String> = linkedSetOf()
    private var suppressNextProfilePreset = true

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hwidLabel = findViewById(R.id.hwidLabel)
        accountLabel = findViewById(R.id.accountLabel)
        statusLabel = findViewById(R.id.statusLabel)
        trafficLabel = findViewById(R.id.trafficLabel)
        serverSpinner = findViewById(R.id.serverSpinner)
        serverStateLabel = findViewById(R.id.serverStateLabel)
        tunStackSpinner = findViewById(R.id.tunStackSpinner)
        profileSpinner = findViewById(R.id.profileSpinner)
        muxProtocolSpinner = findViewById(R.id.muxProtocolSpinner)
        muxStreamsSpinner = findViewById(R.id.muxStreamsSpinner)
        customStreamsInput = findViewById(R.id.customStreamsInput)
        gamerSection = findViewById(R.id.gamerSection)
        gamerSearchInput = findViewById(R.id.gamerSearchInput)
        gamerAppsList = findViewById(R.id.gamerAppsList)
        gamerManualPackageInput = findViewById(R.id.gamerManualPackageInput)
        gamerAddPackageButton = findViewById(R.id.gamerAddPackageButton)
        customProxySection = findViewById(R.id.customProxySection)
        customProxyHostInput = findViewById(R.id.customProxyHostInput)
        customProxyPortInput = findViewById(R.id.customProxyPortInput)
        customPayload1Input = findViewById(R.id.customPayload1Input)
        customPayload2Input = findViewById(R.id.customPayload2Input)
        toggleVpnButton = findViewById(R.id.startVpnButton)
        batteryButton = findViewById(R.id.batteryButton)
        shareNetButton = findViewById(R.id.shareProxyButton)

        val stackValues = listOf("gvisor", "system", "mixed")
        val stackAdapter = ArrayAdapter(this, R.layout.spinner_item_selected, stackValues)
        stackAdapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
        tunStackSpinner.adapter = stackAdapter

        val profileValues = listOf("low_end", "battery", "normal", "ultra", "gamer", "custom", "custom_proxy")
        val profileLabels = listOf("Gama baja", "Ahorro batería", "Normal", "Ultra", "Gamer", "Personalizado", "Servidor propio")
        val profileAdapter = ArrayAdapter(this, R.layout.spinner_item_selected, profileLabels)
        profileAdapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
        profileSpinner.adapter = profileAdapter

        val muxValues = listOf("smux", "h2mux")
        val muxAdapter = ArrayAdapter(this, R.layout.spinner_item_selected, muxValues)
        muxAdapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
        muxProtocolSpinner.adapter = muxAdapter

        hwid = BlackTunnelClient.getOrCreateHwid(noBackupFilesDir)
        hwidLabel.text = "HWID: $hwid"

        val savedStack = AppSettings.getTunStack(this)
        tunStackSpinner.setSelection(stackValues.indexOf(savedStack).coerceAtLeast(0))
        val savedProfile = AppSettings.getPerformanceProfile(this)
        profileSpinner.setSelection(profileValues.indexOf(savedProfile).coerceAtLeast(0))
        val savedMux = AppSettings.getMuxProtocol(this)
        muxProtocolSpinner.setSelection(muxValues.indexOf(savedMux).coerceAtLeast(0))
        bindMuxStreamsOptions(savedMux, AppSettings.getMuxMaxStreams(this, savedMux))
        customStreamsInput.setText(AppSettings.getCustomMuxMaxStreams(this).toString())
        selectedGamerPackages = AppSettings.getGamerTargetPackages(this).toMutableSet()
        customProxyHostInput.setText(AppSettings.getCustomProxyHost(this))
        customProxyPortInput.setText(AppSettings.getCustomProxyPort(this).toString())
        customPayload1Input.setText(AppSettings.getCustomPayload1(this))
        customPayload2Input.setText(AppSettings.getCustomPayload2(this))
        setupGamerAppsUi()
        renderProfileUi(savedProfile)

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
        profileSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val profile = profileValues.getOrElse(position) { "normal" }
                if (suppressNextProfilePreset) {
                    suppressNextProfilePreset = false
                    renderProfileUi(profile)
                    saveConfigFromInputs(showToast = false)
                    return
                }
                applyProfilePreset(profile, muxValues)
                renderProfileUi(profile)
                saveConfigFromInputs(showToast = false)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        muxProtocolSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val protocol = muxProtocolSpinner.selectedItem?.toString().orEmpty()
                bindMuxStreamsOptions(protocol, AppSettings.getMuxMaxStreams(this@MainActivity, protocol))
                saveConfigFromInputs(showToast = false)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        muxStreamsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveConfigFromInputs(showToast = false)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        customStreamsInput.doAfterTextChanged {
            saveConfigFromInputs(showToast = false)
        }
        customProxyHostInput.doAfterTextChanged { saveConfigFromInputs(showToast = false) }
        customProxyPortInput.doAfterTextChanged { saveConfigFromInputs(showToast = false) }
        customPayload1Input.doAfterTextChanged { saveConfigFromInputs(showToast = false) }
        customPayload2Input.doAfterTextChanged { saveConfigFromInputs(showToast = false) }
        gamerSearchInput.doAfterTextChanged {
            filterGamerApps(it?.toString().orEmpty())
        }
        gamerAddPackageButton.setOnClickListener {
            addManualGamerPackage()
        }
        gamerAppsList.setOnItemClickListener { _, _, position, _ ->
            val selected = filteredLaunchableApps.getOrNull(position) ?: return@setOnItemClickListener
            val pkg = selected.second
            val nowSelected = if (selectedGamerPackages.contains(pkg)) {
                selectedGamerPackages.remove(pkg)
                false
            } else {
                selectedGamerPackages.add(pkg)
                true
            }
            AppSettings.setGamerTargetPackages(this, selectedGamerPackages)
            filterGamerApps(gamerSearchInput.text.toString())
            saveConfigFromInputs(showToast = false)
            val msg = if (nowSelected) {
                getString(R.string.gamer_selected_app, selected.first)
            } else {
                "App gamer deseleccionada: ${selected.first}"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
            val now = System.currentTimeMillis()
            if (now - lastToggleAtMs < 1200L) return@setOnClickListener
            lastToggleAtMs = now
            if (isVpnConnected) {
                stopVpnNow()
            } else {
                showTunCompatibilityWarningThen { authenticateThenStart() }
            }
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
        stopTrafficUpdates()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshPersistentConnectionState()
        refreshBatteryButtonVisibility()
        startTrafficUpdates()
    }

    private fun saveConfigFromInputs(showToast: Boolean): Boolean {
        val stack = tunStackSpinner.selectedItem?.toString().orEmpty()
        val profileValues = listOf("low_end", "battery", "normal", "ultra", "gamer", "custom", "custom_proxy")
        val selectedProfile = profileValues.getOrElse(profileSpinner.selectedItemPosition.coerceAtLeast(0)) { "normal" }
        val muxProtocol = muxProtocolSpinner.selectedItem?.toString().orEmpty()

        val muxStreams = if (selectedProfile == "custom") {
            customStreamsInput.text.toString().toIntOrNull()
        } else {
            muxStreamsSpinner.selectedItem?.toString()?.toIntOrNull()
        }

        if (muxStreams == null || muxStreams <= 0) {
            if (showToast) Toast.makeText(this, "MUX inválido", Toast.LENGTH_SHORT).show()
            return false
        }

        AppSettings.setTunStack(this, stack)
        AppSettings.setPerformanceProfile(this, selectedProfile)
        AppSettings.setMuxProtocol(this, muxProtocol)
        if (selectedProfile == "custom") {
            AppSettings.setCustomMuxMaxStreams(this, muxStreams)
        }
        AppSettings.setMuxMaxStreams(this, muxProtocol, muxStreams)
        if (selectedProfile == "gamer") AppSettings.setGamerTargetPackages(this, selectedGamerPackages)

        val customHost = customProxyHostInput.text.toString().trim()
        val customPort = customProxyPortInput.text.toString().trim().toIntOrNull()
        val payload1 = customPayload1Input.text.toString()
        val payload2 = customPayload2Input.text.toString()
        if (selectedProfile == "custom_proxy") {
            if (customHost.isBlank()) {
                if (showToast) Toast.makeText(this, getString(R.string.custom_proxy_host_required), Toast.LENGTH_LONG).show()
                return false
            }
            if (customPort == null || customPort !in 1..65535) {
                if (showToast) Toast.makeText(this, getString(R.string.custom_proxy_port_required), Toast.LENGTH_LONG).show()
                return false
            }
            if (payload2.isBlank()) {
                if (showToast) Toast.makeText(this, getString(R.string.custom_payload2_required), Toast.LENGTH_LONG).show()
                return false
            }
        }
        AppSettings.setCustomProxyHost(this, customHost)
        if (customPort != null) AppSettings.setCustomProxyPort(this, customPort)
        AppSettings.setCustomPayload1(this, payload1)
        AppSettings.setCustomPayload2(this, payload2)

        if (showToast) Toast.makeText(this, getString(R.string.config_saved), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun applyProfilePreset(profile: String, muxValues: List<String>) {
        when (profile) {
            "low_end" -> {
                tunStackSpinner.setSelection(listOf("gvisor", "system", "mixed").indexOf("system"))
                muxProtocolSpinner.setSelection(muxValues.indexOf("h2mux").coerceAtLeast(0))
                bindMuxStreamsOptions("h2mux", 700)
            }
            "battery" -> {
                tunStackSpinner.setSelection(listOf("gvisor", "system", "mixed").indexOf("system"))
                muxProtocolSpinner.setSelection(muxValues.indexOf("h2mux").coerceAtLeast(0))
                bindMuxStreamsOptions("h2mux", 1000)
            }
            "normal" -> {
                tunStackSpinner.setSelection(listOf("gvisor", "system", "mixed").indexOf("gvisor"))
                muxProtocolSpinner.setSelection(muxValues.indexOf("smux").coerceAtLeast(0))
                bindMuxStreamsOptions("smux", 5000)
            }
            "ultra" -> {
                tunStackSpinner.setSelection(listOf("gvisor", "system", "mixed").indexOf("gvisor"))
                muxProtocolSpinner.setSelection(muxValues.indexOf("smux").coerceAtLeast(0))
                bindMuxStreamsOptions("smux", 12000)
            }
            "gamer" -> {
                tunStackSpinner.setSelection(listOf("gvisor", "system", "mixed").indexOf("system"))
                muxProtocolSpinner.setSelection(muxValues.indexOf("smux").coerceAtLeast(0))
                bindMuxStreamsOptions("smux", 15000)
            }
            else -> {
                // custom: mantiene selección actual
            }
        }
    }

    private fun renderProfileUi(profile: String) {
        val isCustom = profile == "custom"
        val isGamer = profile == "gamer"
        val isCustomProxy = profile == "custom_proxy"
        muxStreamsSpinner.visibility = if (isCustom || isCustomProxy) android.view.View.GONE else android.view.View.VISIBLE
        customStreamsInput.visibility = if (isCustom) android.view.View.VISIBLE else android.view.View.GONE
        gamerSection.visibility = if (isGamer) android.view.View.VISIBLE else android.view.View.GONE
        customProxySection.visibility = if (isCustomProxy) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setupGamerAppsUi() {
        gamerAppsAdapter = ArrayAdapter(this, R.layout.spinner_item_dropdown, mutableListOf())
        gamerAppsList.adapter = gamerAppsAdapter
        thread(name = "apps-loader") {
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchable = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                .map {
                    val label = it.loadLabel(pm).toString().ifBlank { it.activityInfo.packageName }
                    label to it.activityInfo.packageName
                }

            val installed = pm.getInstalledApplications(PackageManager.MATCH_ALL)
                .asSequence()
                .filterNot { it.packageName == packageName }
                .filter { app ->
                    val hasCode = (app.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
                    val isInstalled = (app.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                    hasCode && isInstalled
                }
                .map { app ->
                    val label = pm.getApplicationLabel(app).toString().ifBlank { app.packageName }
                    label to app.packageName
                }
                .toList()

            val storedManual = selectedGamerPackages
                .filter { pkg -> installed.none { it.second == pkg } && launchable.none { it.second == pkg } }
                .map { it to it }

            val apps = (launchable + installed + storedManual)
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }

            runOnUiThread {
                allLaunchableApps = apps
                filterGamerApps(gamerSearchInput.text?.toString().orEmpty())
            }
        }
    }

    private fun filterGamerApps(query: String) {
        val normalized = query.trim().lowercase()
        val base = if (normalized.isBlank()) {
            allLaunchableApps
        } else {
            allLaunchableApps.filter { (label, pkg) ->
                label.lowercase().contains(normalized) || pkg.lowercase().contains(normalized)
            }
        }
        filteredLaunchableApps = base.sortedWith(
            compareByDescending<Pair<String, String>> { selectedGamerPackages.contains(it.second) }
                .thenBy { it.first.lowercase() }
        )
        val rows = filteredLaunchableApps.map { (label, pkg) ->
            val picked = if (selectedGamerPackages.contains(pkg)) " ✅" else ""
            "$label ($pkg)$picked"
        }
        gamerAppsAdapter.clear()
        gamerAppsAdapter.addAll(rows)
        gamerAppsAdapter.notifyDataSetChanged()
    }

    private fun addManualGamerPackage() {
        val raw = gamerManualPackageInput.text?.toString().orEmpty().trim().lowercase()
        if (!isValidPackageName(raw)) {
            Toast.makeText(this, getString(R.string.gamer_invalid_package), Toast.LENGTH_SHORT).show()
            return
        }

        if (!selectedGamerPackages.add(raw)) {
            Toast.makeText(this, getString(R.string.gamer_manual_already_added), Toast.LENGTH_SHORT).show()
            return
        }

        if (allLaunchableApps.none { it.second == raw }) {
            allLaunchableApps = (allLaunchableApps + listOf(raw to raw)).sortedBy { it.first.lowercase() }
        }

        gamerManualPackageInput.setText("")
        AppSettings.setGamerTargetPackages(this, selectedGamerPackages)
        filterGamerApps(gamerSearchInput.text?.toString().orEmpty())
        saveConfigFromInputs(showToast = false)
        Toast.makeText(this, getString(R.string.gamer_manual_added, raw), Toast.LENGTH_SHORT).show()
    }

    private fun isValidPackageName(value: String): Boolean {
        if (value.length < 3 || value.length > 255) return false
        if (!value.contains('.')) return false
        return Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$").matches(value)
    }

    private fun bindMuxStreamsOptions(protocol: String, preferred: Int) {
        val options = listOf(700, 1000, 2000, 3000, 5000, 12000, 15000, 20000)
        val adapter = ArrayAdapter(this, R.layout.spinner_item_selected, options.map { it.toString() })
        adapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
        muxStreamsSpinner.adapter = adapter
        val idx = options.indexOf(preferred.coerceIn(options.first(), options.last())).let { if (it >= 0) it else 0 }
        muxStreamsSpinner.setSelection(idx, false)
    }

    private fun startTrafficUpdates() {
        lastTrafficUp = 0L
        lastTrafficDown = 0L
        lastTrafficTs = 0L
        trafficUpdateHandler.removeCallbacks(trafficTicker)
        trafficUpdateHandler.post(trafficTicker)
    }

    private fun stopTrafficUpdates() {
        trafficUpdateHandler.removeCallbacks(trafficTicker)
    }

    private val trafficTicker = object : Runnable {
        override fun run() {
            val snapshot = BlackTunnelClient.getTrafficSnapshot()
            val now = System.currentTimeMillis()
            val deltaMs = (now - lastTrafficTs).coerceAtLeast(1L)
            val upRate = if (lastTrafficTs == 0L) 0L else ((snapshot.uplinkBytes - lastTrafficUp).coerceAtLeast(0L) * 1000L) / deltaMs
            val downRate = if (lastTrafficTs == 0L) 0L else ((snapshot.downlinkBytes - lastTrafficDown).coerceAtLeast(0L) * 1000L) / deltaMs
            lastTrafficUp = snapshot.uplinkBytes
            lastTrafficDown = snapshot.downlinkBytes
            lastTrafficTs = now
            trafficLabel.text = getString(
                R.string.traffic_status,
                formatBytesPerSecond(downRate),
                formatBytesPerSecond(upRate)
            )
            trafficUpdateHandler.postDelayed(this, 1200L)
        }
    }

    private fun formatBytesPerSecond(value: Long): String {
        val kb = value / 1024.0
        return if (kb >= 1024.0) String.format("%.1f MB/s", kb / 1024.0) else String.format("%.0f KB/s", kb)
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

        val activeProfile = AppSettings.getPerformanceProfile(this)
        val tunnelDomain = AppSettings.getTunnelDomain(this)

        if (activeProfile == "custom_proxy") {
            lastAuthOk = true
            lastAuthDomain = tunnelDomain
            updateUiState(verified = true, connected = false, status = getString(R.string.status_connecting))
            requestVpnPermissionAndStart()
            return
        }

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

    private fun showTunCompatibilityWarningThen(onContinue: () -> Unit) {
        if (AppSettings.shouldSkipTunWarning(this)) {
            onContinue()
            return
        }

        val dontShowAgain = CheckBox(this).apply {
            text = getString(R.string.tun_warning_do_not_show)
            setPadding(32, 0, 0, 0)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tun_warning_title))
            .setMessage(getString(R.string.tun_warning_message))
            .setView(dontShowAgain)
            .setCancelable(true)
            .setPositiveButton(getString(R.string.tun_warning_continue)) { _, _ ->
                if (dontShowAgain.isChecked) AppSettings.setSkipTunWarning(this, true)
                onContinue()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

            val activeProfile = AppSettings.getPerformanceProfile(this)
            val activeProtocol = AppSettings.getMuxProtocol(this)
            val activeStreams = if (activeProfile == "custom") {
                AppSettings.getCustomMuxMaxStreams(this)
            } else {
                AppSettings.getMuxMaxStreams(this, activeProtocol)
            }

            val serviceIntent = Intent(this, LocalVpnService::class.java)
                .setAction(LocalVpnService.ACTION_START)
                .putExtra(LocalVpnService.EXTRA_HWID, hwid)
                .putExtra(LocalVpnService.EXTRA_TUNNEL_DOMAIN, lastAuthDomain)
                .putExtra(LocalVpnService.EXTRA_TUN_STACK, AppSettings.getTunStack(this))
                .putExtra(LocalVpnService.EXTRA_MUX_PROTOCOL, activeProtocol)
                .putExtra(LocalVpnService.EXTRA_SMUX_MAX_STREAMS, activeStreams)
                .putExtra(LocalVpnService.EXTRA_PERFORMANCE_PROFILE, activeProfile)
                .putStringArrayListExtra(LocalVpnService.EXTRA_GAMER_PACKAGES, ArrayList(AppSettings.getGamerTargetPackages(this)))
                .putExtra(LocalVpnService.EXTRA_CUSTOM_PROXY_HOST, AppSettings.getCustomProxyHost(this))
                .putExtra(LocalVpnService.EXTRA_CUSTOM_PROXY_PORT, AppSettings.getCustomProxyPort(this))
                .putExtra(LocalVpnService.EXTRA_CUSTOM_PAYLOAD1, AppSettings.getCustomPayload1(this))
                .putExtra(LocalVpnService.EXTRA_CUSTOM_PAYLOAD2, AppSettings.getCustomPayload2(this))

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
        val addresses = getLocalIpv4Addresses()
        val primaryIp = addresses.firstOrNull()?.first ?: "192.168.43.1"
        val candidates = if (addresses.isEmpty()) {
            "• 192.168.43.1 (fallback)"
        } else {
            addresses.joinToString("\n") { (ip, iface) -> "• $ip ($iface)" }
        }
        val message = getString(R.string.share_proxy_text, primaryIp, BlackTunnelClient.LOCAL_PORT) +
            "\n\nIPs detectadas en este teléfono:\n$candidates"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.share_proxy_title))
            .setMessage(message)
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

    private fun getLocalIpv4Addresses(): List<Pair<String, String>> {
        return runCatching {
            val result = mutableListOf<Pair<String, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress.orEmpty().substringBefore('%')
                        if (ip.isNotBlank()) result.add(ip to iface.name)
                    }
                }
            }
            result
                .distinctBy { it.first }
                .sortedWith(compareBy<Pair<String, String>> { interfacePriority(it.second) }.thenBy { InetAddress.getByName(it.first).address.last().toInt() })
        }.getOrDefault(emptyList())
    }

    private fun interfacePriority(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.startsWith("ap") || lower.contains("hotspot") -> 0
            lower.startsWith("wlan") || lower.startsWith("swlan") || lower.contains("wifi") -> 1
            lower.startsWith("rndis") || lower.contains("usb") -> 2
            else -> 3
        }
    }

    companion object {
        private const val REQUEST_CODE_PREPARE_VPN = 5001
    }
}
