package com.remotetap.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.remotetap.databinding.ActivityClientBinding
import com.remotetap.repository.CommandRepository
import com.remotetap.repository.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ClientActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientBinding
    private lateinit var prefs: PreferencesRepository
    private lateinit var commandRepo: CommandRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesRepository(this)
        commandRepo = CommandRepository(prefs.ntfyServerUrl, prefs.ntfyAccessToken, prefs.pairingCode)

        binding.btnPress.setOnClickListener { sendPressCommand() }

        binding.btnResetPairing.setOnClickListener {
            prefs.clearAll()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
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
