package com.remotetap.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.remotetap.databinding.ActivityMainBinding
import com.remotetap.repository.DeviceRole
import com.remotetap.repository.PreferencesRepository

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesRepository(this)

        // ntfy must be configured before anything else
        if (!prefs.isNtfyConfigured()) {
            startActivity(Intent(this, NtfySetupActivity::class.java))
            finish()
            return
        }

        // Skip role selection if already configured
        when (prefs.role) {
            DeviceRole.SERVER -> { startActivity(Intent(this, ServerActivity::class.java)); finish() }
            DeviceRole.CLIENT -> { startActivity(Intent(this, ClientActivity::class.java)); finish() }
            DeviceRole.NONE -> showRoleSelection()
        }
    }

    private fun showRoleSelection() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnServer.setOnClickListener {
            prefs.role = DeviceRole.SERVER
            startActivity(Intent(this, PairingActivity::class.java))
        }

        binding.btnClient.setOnClickListener {
            prefs.role = DeviceRole.CLIENT
            startActivity(Intent(this, PairingActivity::class.java))
        }
    }
}
