package com.remotetap.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import com.remotetap.databinding.ActivityServerBinding
import com.remotetap.repository.PreferencesRepository
import com.remotetap.service.CommandListenerService
import com.remotetap.service.RemoteTapAccessibilityService

class ServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerBinding
    private lateinit var prefs: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        binding.tvPairingCode.text = "Pairing code: ${prefs.pairingCode}"

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnRecordButton.setOnClickListener {
            startActivity(Intent(this, ButtonRecordingActivity::class.java))
        }

        binding.btnResetPairing.setOnClickListener {
            prefs.clearAll()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()

        // Start listener service if accessibility is enabled
        if (isAccessibilityEnabled()) {
            startForegroundService(Intent(this, CommandListenerService::class.java))
        }
    }

    private fun updateStatus() {
        val accessibilityOk = isAccessibilityEnabled()
        val buttonConfigured = prefs.buttonConfig != null

        binding.statusAccessibility.text = if (accessibilityOk) "Accessibility: Enabled" else "Accessibility: Disabled (tap to enable)"
        binding.statusButton.text = if (buttonConfigured) "Button: Recorded" else "Button: Not recorded yet"
        binding.btnRecordButton.isEnabled = accessibilityOk
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }
}
