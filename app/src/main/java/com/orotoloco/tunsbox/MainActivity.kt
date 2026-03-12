package com.orotoloco.tunsbox

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var connectButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)

        statusText.text = if (BuildConfig.HAS_SINGBOX_BINARIES) {
            "Estado: listo (binario sing-box integrado)"
        } else {
            "Estado: falta binario en assets/sing-box/arm64-v8a/sing-box"
        }

        connectButton.setOnClickListener {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, REQUEST_VPN)
            } else {
                ContextCompat.startForegroundService(this, Intent(this, TunVpnService::class.java).apply {
                    action = TunVpnService.ACTION_CONNECT
                })
                statusText.text = "Estado: conectando..."
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == Activity.RESULT_OK) {
            ContextCompat.startForegroundService(this, Intent(this, TunVpnService::class.java).apply {
                action = TunVpnService.ACTION_CONNECT
            })
            statusText.text = "Estado: conectando..."
        }
    }

    companion object {
        private const val REQUEST_VPN = 1001
    }
}
