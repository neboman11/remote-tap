package com.remotetap.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.remotetap.repository.DeviceRole
import com.remotetap.repository.PreferencesRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PreferencesRepository(context)
        if (prefs.role == DeviceRole.SERVER && prefs.pairingCode.isNotEmpty()) {
            context.startForegroundService(Intent(context, CommandListenerService::class.java))
        }
    }
}
