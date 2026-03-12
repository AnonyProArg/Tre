package com.example.localvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var logsTextView: TextView

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            appendLogLine(line)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logsTextView = findViewById(R.id.logsTextView)

        findViewById<Button>(R.id.startVpnButton).setOnClickListener {
            requestVpnPermissionAndStart()
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
