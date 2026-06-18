package com.remotetap.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityButtonRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        binding.btnStartRecording.setOnClickListener {
            if (isRecording()) stopRecordingMode() else startRecordingMode()
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

    private fun startRecordingMode() {
        val service = RemoteTapAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Accessibility service is not running — enable it in Settings first.", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnStartRecording.text = "Cancel"
        binding.tvInstructions.text = "Now switch to the target app and tap the button you want to record. You have 30 seconds."

        service.startRecordingMode { config ->
            runOnUiThread {
                handler.removeCallbacks(recordingTimeout)
                if (config != null) {
                    Toast.makeText(this, "Recorded: ${config.text.ifEmpty { config.contentDescription.ifEmpty { config.viewId } }}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Nothing recorded.", Toast.LENGTH_SHORT).show()
                }
                resetRecordingUI()
            }
        }

        // Cancel automatically after 30s so the user isn't stuck in recording mode
        handler.postDelayed(recordingTimeout, 30_000L)

        // Bring the previous app to the foreground
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
            val label = config.text.ifEmpty { config.contentDescription.ifEmpty { config.viewId } }
            binding.tvCurrentButton.text = "Recorded: $label\n(${config.packageName})"
            binding.btnClearButton.isEnabled = true
        } else {
            binding.tvCurrentButton.text = "No button recorded yet"
            binding.btnClearButton.isEnabled = false
        }
    }

    private fun getDefaultInstructions() =
        "Open the target app and navigate to the screen with the button. Come back here, tap 'Record button', then tap the button in that app."
}
