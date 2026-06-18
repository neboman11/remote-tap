package com.remotetap.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.remotetap.databinding.ActivityNtfySetupBinding
import com.remotetap.repository.CommandRepository
import com.remotetap.repository.PreferencesRepository
import kotlinx.coroutines.launch

class NtfySetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNtfySetupBinding
    private lateinit var prefs: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNtfySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        // Pre-fill if already configured (e.g. user came back to update)
        if (prefs.ntfyServerUrl.isNotEmpty()) binding.etServerUrl.setText(prefs.ntfyServerUrl)
        if (prefs.ntfyAccessToken.isNotEmpty()) binding.etAccessToken.setText(prefs.ntfyAccessToken)

        binding.btnTestAndSave.setOnClickListener { testAndSave() }
    }

    private fun testAndSave() {
        val url = binding.etServerUrl.text.toString().trim()
        val token = binding.etAccessToken.text.toString().trim()

        if (url.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestAndSave.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvTestResult.text = "Testing connection..."

        lifecycleScope.launch {
            val repo = CommandRepository(url.trimEnd('/'), token, "test")
            val error = repo.testConnection()
            binding.progressBar.visibility = View.GONE
            binding.btnTestAndSave.isEnabled = true

            if (error != null) {
                binding.tvTestResult.text = "Failed: $error"
            } else {
                prefs.ntfyServerUrl = url
                prefs.ntfyAccessToken = token
                binding.tvTestResult.text = "Connected!"
                startActivity(Intent(this@NtfySetupActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}
