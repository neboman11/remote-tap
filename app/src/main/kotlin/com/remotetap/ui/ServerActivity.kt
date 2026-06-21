package com.remotetap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotetap.databinding.ActivityServerBinding
import com.remotetap.repository.PreferencesRepository
import com.remotetap.service.CommandListenerService
import com.remotetap.service.RemoteTapAccessibilityService

class ServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerBinding
    private lateinit var prefs: PreferencesRepository

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled on next onResume */ }

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
        requestNotificationPermission()
        requestBatteryOptimizationExemption()

        // Start listener service if accessibility is enabled
        if (isAccessibilityEnabled()) {
            startForegroundService(Intent(this, CommandListenerService::class.java))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
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
