package com.remotetap.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.remotetap.repository.DeviceRole
import com.remotetap.repository.PreferencesRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PreferencesRepository(context)
        if (prefs.role == DeviceRole.SERVER && prefs.pairingCode.isNotEmpty()) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteTap:BootWakeLock")
            wake.acquire(10_000L)
            context.startForegroundService(Intent(context, CommandListenerService::class.java))
            wake.release()
        }
    }
}
