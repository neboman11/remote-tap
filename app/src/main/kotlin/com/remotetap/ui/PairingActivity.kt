package com.remotetap.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remotetap.databinding.ActivityPairingBinding
import com.remotetap.repository.DeviceRole
import com.remotetap.repository.PreferencesRepository
import java.util.UUID

class PairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingBinding
    private lateinit var prefs: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        if (prefs.role == DeviceRole.SERVER) {
            setupServerPairing()
        } else {
            setupClientPairing()
        }
    }

    private fun setupServerPairing() {
        // Server generates the pairing code
        val code = generatePairingCode()
        prefs.pairingCode = code
        if (prefs.deviceId.isEmpty()) prefs.deviceId = UUID.randomUUID().toString()

        binding.labelInstruction.text = "Share this code with your remote phone:"
        binding.tvPairingCode.text = code
        binding.tilPairingCode.visibility = android.view.View.GONE
        binding.btnConfirm.text = "Continue"
        binding.btnConfirm.setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
            finish()
        }
    }

    private fun setupClientPairing() {
        // Client enters the code shown on the server phone
        binding.labelInstruction.text = "Enter the code shown on the host phone:"
        binding.tvPairingCode.visibility = android.view.View.GONE
        binding.btnConfirm.text = "Connect"
        binding.btnConfirm.setOnClickListener {
            val code = binding.etPairingCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "Please enter the 6-character code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.pairingCode = code
            if (prefs.deviceId.isEmpty()) prefs.deviceId = UUID.randomUUID().toString()
            startActivity(Intent(this, ClientActivity::class.java))
            finish()
        }
    }

    private fun generatePairingCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no ambiguous chars
        return (1..6).map { chars.random() }.joinToString("")
    }
}
