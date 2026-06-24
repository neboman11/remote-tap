package com.remotetap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.remotetap.databinding.ActivityClientBinding
import com.remotetap.repository.CommandRepository
import com.remotetap.repository.PreferencesRepository
import com.remotetap.service.NotificationRelayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ClientActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled on next onResume */ }
    private lateinit var prefs: PreferencesRepository
    private lateinit var commandRepo: CommandRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesRepository(this)
        commandRepo = CommandRepository(prefs.ntfyServerUrl, prefs.ntfyAccessToken, prefs.pairingCode)

        binding.tvTitle.text = "Remote Phone"

        binding.btnPress.setOnClickListener { sendPressCommand() }

        binding.btnResetPairing.setOnClickListener {
            prefs.clearAll()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        startForegroundService(Intent(this, NotificationRelayService::class.java))
        requestNotificationPermission()
        requestBatteryOptimizationExemption()
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

    private fun sendPressCommand() {
        binding.btnPress.isEnabled = false
        binding.tvStatus.text = "Sending..."

        lifecycleScope.launch {
            try {
                // Capture timestamp before subscribing to avoid race where result arrives
                // before we start listening. ntfy ?since= will include anything cached after this.
                val sinceMs = System.currentTimeMillis()
                val commandId = commandRepo.sendCommand()
                binding.tvStatus.text = "Waiting for response..."

                val result = withTimeoutOrNull(10_000L) {
                    commandRepo.observeResult(commandId, sinceMs).first()
                }

                binding.tvStatus.text = when {
                    result == null -> "No response — is the other phone online?"
                    result.success -> "Done!"
                    else -> "Failed: ${result.errorMessage}"
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message}"
            } finally {
                binding.btnPress.isEnabled = true
            }
        }
    }
}
