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
        if (prefs.pairingCode.isEmpty()) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteTap:BootWakeLock")
        wake.acquire(10_000L)
        when (prefs.role) {
            DeviceRole.SERVER -> context.startForegroundService(Intent(context, CommandListenerService::class.java))
            DeviceRole.CLIENT -> context.startForegroundService(Intent(context, NotificationRelayService::class.java))
            DeviceRole.NONE -> Unit
        }
        wake.release()
    }
}
