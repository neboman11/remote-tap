package com.remotetap.ui

import android.Manifest
import android.companion.AssociationRequest
import android.content.pm.LauncherApps
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotetap.databinding.ActivityServerBinding
import com.remotetap.repository.PreferencesRepository
import com.remotetap.service.CommandListenerService

class ServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerBinding
    private lateinit var prefs: PreferencesRepository

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled on next onResume */ }

    private val companionAssocLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        binding.tvTitle.text = "Host Phone"
        binding.tvPairingCode.text = "Pairing code: ${prefs.pairingCode}"

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnRecordButton.setOnClickListener {
            startActivity(Intent(this, ButtonRecordingActivity::class.java))
        }

        binding.btnPairCompanion.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startWatchAssociation()
        }

        binding.btnEnableNotificationListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnPickApp.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        binding.btnConfigureSmsTrigger.setOnClickListener {
            startActivity(Intent(this, SmsTriggerActivity::class.java))
        }

        binding.btnResetPairing.setOnClickListener {
            prefs.clearAll()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            binding.btnPairCompanion.isEnabled = false
            binding.btnPairCompanion.text = "Pair as watch companion (requires Android 12+)"
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        requestNotificationPermission()
        requestBatteryOptimizationExemption()

        if (isAccessibilityEnabled()) {
            startForegroundService(Intent(this, CommandListenerService::class.java))
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startWatchAssociation() {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val request = AssociationRequest.Builder()
            .setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            .build()

        cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    companionAssocLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                }
            }

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onAssociationPending(chooserLauncher: IntentSender) {
                companionAssocLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }

            override fun onFailure(error: CharSequence?) {
                binding.statusCompanion.text = "Companion pairing failed: $error"
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun watchCompanionLabel(): String? {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations
                .firstOrNull { it.deviceProfile == AssociationRequest.DEVICE_PROFILE_WATCH }
                ?.displayName?.toString()
                ?: if (cdm.myAssociations.isNotEmpty()) "Paired" else null
        } else {
            @Suppress("DEPRECATION")
            if (cdm.associations.isNotEmpty()) "Paired" else null
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
        val notifListenerOk = isNotificationListenerEnabled()
        val watchedPkg = prefs.watchedPackageName

        binding.statusAccessibility.text =
            if (accessibilityOk) "Accessibility: Enabled" else "Accessibility: Disabled (tap to enable)"
        binding.statusButton.text =
            if (buttonConfigured) "Button: Recorded" else "Button: Not recorded yet"
        binding.btnRecordButton.isEnabled = accessibilityOk

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val label = watchCompanionLabel()
            binding.statusCompanion.text =
                if (label != null) "Watch companion: $label (cross-profile enabled)"
                else "Watch companion: Not paired"
            binding.btnPairCompanion.isEnabled = label == null
        } else {
            binding.statusCompanion.text = "Watch companion: Requires Android 12+"
        }

        binding.statusNotificationListener.text =
            if (notifListenerOk) "Notification access: Granted"
            else "Notification access: Not granted (pair above, or grant manually)"
        binding.statusWatchedApp.text = if (watchedPkg.isEmpty()) {
            "Monitored app: None"
        } else {
            val watchedSerial = prefs.watchedUserSerial
            val um = getSystemService(UserManager::class.java)
            val userHandle = if (watchedSerial != -1L) um.getUserForSerialNumber(watchedSerial) else null
            val label = runCatching {
                if (userHandle != null) {
                    val launcherApps = getSystemService(LauncherApps::class.java)
                    launcherApps.getApplicationInfo(watchedPkg, 0, userHandle)
                        .loadLabel(packageManager).toString()
                } else {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(watchedPkg, 0)
                    ).toString()
                }
            }.getOrDefault(watchedPkg)
            val isWork = userHandle != null && userHandle != Process.myUserHandle()
            "Monitored app: $label${if (isWork) " (Work)" else ""}"
        }
        binding.btnPickApp.isEnabled = notifListenerOk

        val smsTriggerMessage = prefs.smsTriggerMessage
        val smsContactCount = prefs.smsContacts.size
        binding.statusSmsTrigger.text = when {
            smsTriggerMessage.isEmpty() -> "SMS trigger: Not configured"
            smsContactCount == 0 -> "SMS trigger: Message set, no contacts allowed"
            else -> "SMS trigger: \"$smsTriggerMessage\" from $smsContactCount contact(s)"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }
}
