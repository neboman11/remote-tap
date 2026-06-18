package com.remotetap.ui

import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.remotetap.databinding.ActivityButtonRecordingBinding
import com.remotetap.repository.PreferencesRepository
import com.remotetap.service.RemoteTapAccessibilityService

class ButtonRecordingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityButtonRecordingBinding
    private lateinit var prefs: PreferencesRepository
    private val handler = Handler(Looper.getMainLooper())
    private val recordingTimeout = Runnable {
        if (isRecording()) stopRecordingMode(timedOut = true)
    }

    private var targetPackage: String? = null
    private var targetAppLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityButtonRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        binding.btnSelectApp.setOnClickListener { showAppPicker() }

        binding.btnStartRecording.setOnClickListener {
            if (isRecording()) stopRecordingMode() else startRecordingMode()
        }

        binding.btnPreviewTap.setOnClickListener {
            val service = RemoteTapAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "Accessibility service not running.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Showing tap location for 3 seconds…", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
            service.showTapIndicator()
        }

        binding.btnClearButton.setOnClickListener {
            prefs.buttonConfig = null
            updateStatus()
            Toast.makeText(this, "Button config cleared", Toast.LENGTH_SHORT).show()
        }

        updateStatus()
        binding.tvInstructions.text = getDefaultInstructions()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(recordingTimeout)
        RemoteTapAccessibilityService.instance?.cancelRecordingMode()
    }

    private fun isRecording() = RemoteTapAccessibilityService.instance?.isRecording() == true

    private fun showAppPicker() {
        val pm = packageManager
        val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = pm.queryIntentActivities(launchIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val labels = apps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select target app")
            .setItems(labels) { _, index ->
                targetPackage = apps[index].activityInfo.packageName
                targetAppLabel = labels[index]
                binding.btnSelectApp.text = targetAppLabel
                binding.btnStartRecording.isEnabled = true
                binding.tvInstructions.text = getDefaultInstructions()
            }
            .show()
    }

    private fun startRecordingMode() {
        val service = RemoteTapAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Accessibility service is not running — enable it in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        val pkg = targetPackage ?: return

        binding.btnStartRecording.text = "Cancel"
        binding.tvInstructions.text = "Switch to $targetAppLabel — recording will activate automatically once you're there. Then tap the button."

        service.startRecordingMode(pkg) { config ->
            runOnUiThread {
                handler.removeCallbacks(recordingTimeout)
                if (config != null) {
                    val label = config.text.ifEmpty { config.contentDescription.ifEmpty { config.viewId.ifEmpty { "tap at (${config.boundsInScreen.centerX()}, ${config.boundsInScreen.centerY()})" } } }
                    Toast.makeText(this, "Recorded: $label", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Nothing recorded.", Toast.LENGTH_SHORT).show()
                }
                resetRecordingUI()
            }
        }

        handler.postDelayed(recordingTimeout, 30_000L)
        moveTaskToBack(true)
    }

    private fun stopRecordingMode(timedOut: Boolean = false) {
        RemoteTapAccessibilityService.instance?.cancelRecordingMode()
        if (timedOut) Toast.makeText(this, "Recording timed out.", Toast.LENGTH_SHORT).show()
        resetRecordingUI()
    }

    private fun resetRecordingUI() {
        handler.removeCallbacks(recordingTimeout)
        binding.btnStartRecording.text = "Record button"
        binding.tvInstructions.text = getDefaultInstructions()
        updateStatus()
    }

    private fun updateStatus() {
        val config = prefs.buttonConfig
        if (config != null) {
            val label = config.text.ifEmpty {
                config.contentDescription.ifEmpty {
                    config.viewId.ifEmpty {
                        "tap at (${config.boundsInScreen.centerX()}, ${config.boundsInScreen.centerY()})"
                    }
                }
            }
            binding.tvCurrentButton.text = "Recorded: $label\n(${config.packageName})"
            binding.btnClearButton.isEnabled = true
            binding.btnPreviewTap.isEnabled = true
        } else {
            binding.tvCurrentButton.text = "No button recorded yet"
            binding.btnClearButton.isEnabled = false
            binding.btnPreviewTap.isEnabled = false
        }
    }

    private fun getDefaultInstructions(): String {
        val app = targetAppLabel ?: return "First select the target app, then tap 'Record button' and tap the button in that app."
        return "Tap 'Record button', switch to $app — recording activates automatically. Then tap the button."
    }
}
