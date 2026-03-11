package com.example.localvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.startVpnButton).setOnClickListener {
            requestVpnPermissionAndStart()
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
            startService(Intent(this, LocalVpnService::class.java))
            Toast.makeText(this, "VPN local iniciada", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permiso de VPN denegado", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQUEST_CODE_PREPARE_VPN = 5001
    }
}
