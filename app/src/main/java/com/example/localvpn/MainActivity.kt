package com.example.localvpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var logsTextView: TextView
    private lateinit var hwidLabel: TextView
    private lateinit var accountLabel: TextView
    private lateinit var tunnelDomainInput: EditText

    private var hwid: String = ""

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            appendLogLine(line)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logsTextView = findViewById(R.id.logsTextView)
        hwidLabel = findViewById(R.id.hwidLabel)
        accountLabel = findViewById(R.id.accountLabel)
        tunnelDomainInput = findViewById(R.id.tunnelDomainInput)

        hwid = BlackTunnelClient.getOrCreateHwid(noBackupFilesDir) { VpnLogStore.add(it) }
        hwidLabel.text = "HWID: $hwid"
        tunnelDomainInput.setText(AppSettings.getTunnelDomain(this))

        findViewById<Button>(R.id.saveDomainButton).setOnClickListener {
            val value = tunnelDomainInput.text.toString().trim()
            if (value.isBlank()) {
                Toast.makeText(this, "Dominio inválido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppSettings.setTunnelDomain(this, value)
            Toast.makeText(this, "Dominio guardado", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.copyIdButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hwid", hwid))
            Toast.makeText(this, "HWID copiado", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.startVpnButton).setOnClickListener {
            authenticateThenStart()
        }

        findViewById<Button>(R.id.stopVpnButton).setOnClickListener {
            startService(Intent(this, LocalVpnService::class.java).setAction(LocalVpnService.ACTION_STOP))
            Toast.makeText(this, "VPN detenida", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
            VpnLogStore.clear()
            logsTextView.text = ""
        }

        renderLogSnapshot()
    }

    override fun onStart() {
        super.onStart()
        VpnLogStore.addListener(logListener)
        renderLogSnapshot()
    }

    override fun onStop() {
        VpnLogStore.removeListener(logListener)
        super.onStop()
    }

    private fun authenticateThenStart() {
        val tunnelDomain = tunnelDomainInput.text.toString().trim()
        if (tunnelDomain.isBlank()) {
            Toast.makeText(this, "Debes poner un dominio", Toast.LENGTH_SHORT).show()
            return
        }

        AppSettings.setTunnelDomain(this, tunnelDomain)
        Toast.makeText(this, "Verificando HWID...", Toast.LENGTH_SHORT).show()

        thread(name = "auth-thread") {
            try {
                val info = BlackTunnelClient.auth(hwid, tunnelDomain) { VpnLogStore.add(it) }
                runOnUiThread {
                    accountLabel.text = "Cuenta: ${info.name} | días: ${info.days} | expira: ${info.expire}" +
                        if (info.premium) " | PREMIUM" else ""
                    requestVpnPermissionAndStart()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    accountLabel.text = "Cuenta: ${e.message}"
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
            startService(Intent(this, LocalVpnService::class.java).setAction(LocalVpnService.ACTION_START))
            Toast.makeText(this, "VPN local iniciada", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permiso de VPN denegado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderLogSnapshot() {
        val all = VpnLogStore.snapshot().joinToString("\n")
        logsTextView.text = all
    }

    private fun appendLogLine(line: String) {
        if (logsTextView.text.isNullOrEmpty()) {
            logsTextView.text = line
        } else {
            logsTextView.append("\n$line")
        }
    }

    companion object {
        private const val REQUEST_CODE_PREPARE_VPN = 5001
    }
}
