package com.remotetap.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remotetap.databinding.ActivityButtonRecordingBinding
import com.remotetap.repository.PreferencesRepository
import com.remotetap.service.RemoteTapAccessibilityService

/**
 * Guides the user through recording the target button:
 * 1. Open the target app
 * 2. Come back here and tap "I'm ready"
 * 3. A transparent overlay appears — tap on the target button
 * 4. The accessibility service captures the node info and saves it
 *
 * The overlay approach avoids needing the user to coordinate timing;
 * they tap the exact button they want recorded.
 */
class ButtonRecordingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityButtonRecordingBinding
    private lateinit var prefs: PreferencesRepository
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityButtonRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        binding.btnStartRecording.setOnClickListener {
            if (!isRecording) startRecordingMode() else stopRecordingMode()
        }

        binding.btnClearButton.setOnClickListener {
            prefs.buttonConfig = null
            updateStatus()
            Toast.makeText(this, "Button config cleared", Toast.LENGTH_SHORT).show()
        }

        updateStatus()
    }

    private fun startRecordingMode() {
        val service = RemoteTapAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Accessibility service is not running. Please enable it first.", Toast.LENGTH_LONG).show()
            return
        }

        isRecording = true
        binding.btnStartRecording.text = "Cancel recording"
        binding.tvInstructions.text = "Switch to the target app and tap the button you want to record. The overlay is active."

        service.showRecordingOverlay { config ->
            runOnUiThread {
                if (config != null) {
                    Toast.makeText(this, "Button recorded!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not identify a button at that location.", Toast.LENGTH_SHORT).show()
                }
                stopRecordingMode()
            }
        }

        // Put RemoteTap in background so the target app is visible through the overlay
        moveTaskToBack(true)
    }

    private fun stopRecordingMode() {
        isRecording = false
        RemoteTapAccessibilityService.instance?.cancelRecordingOverlay()
        binding.btnStartRecording.text = "Record button"
        binding.tvInstructions.text = getDefaultInstructions()
        updateStatus()
    }

    private fun updateStatus() {
        val config = prefs.buttonConfig
        if (config != null) {
            val label = config.text.ifEmpty { config.contentDescription.ifEmpty { config.viewId } }
            binding.tvCurrentButton.text = "Recorded button: $label\n(${config.packageName})"
            binding.btnClearButton.isEnabled = true
        } else {
            binding.tvCurrentButton.text = "No button recorded yet"
            binding.btnClearButton.isEnabled = false
        }
    }

    private fun getDefaultInstructions() =
        "Tap 'Record button', then switch to the app and tap the button you want to control remotely."
}
