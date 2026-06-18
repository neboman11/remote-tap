package com.remotetap.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.remotetap.R
import com.remotetap.repository.CommandRepository
import com.remotetap.repository.PreferencesRepository
import com.remotetap.ui.ServerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CommandListenerService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var prefs: PreferencesRepository

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun startListening() {
        val pairingCode = prefs.pairingCode
        val serverUrl = prefs.ntfyServerUrl
        val token = prefs.ntfyAccessToken
        if (pairingCode.isEmpty() || serverUrl.isEmpty() || token.isEmpty()) return

        val repo = CommandRepository(serverUrl, token, pairingCode)
        scope.launch {
            // Reconnect loop: if the streaming connection drops, retry with backoff
            var delayMs = 1_000L
            while (true) {
                runCatching {
                    repo.observeIncomingCommands().collect { command ->
                        delayMs = 1_000L // reset backoff on successful message
                        val ageMs = System.currentTimeMillis() - command.timestampMs
                        Log.d(TAG, "command received id=${command.id} ageMs=$ageMs")
                        // Discard commands queued while the phone was offline (ntfy caches 12h by default)
                        if (ageMs > 30_000L) {
                            Log.d(TAG, "discarding stale command (age=${ageMs}ms)")
                            return@collect
                        }
                        val service = RemoteTapAccessibilityService.instance
                        if (service == null) Log.w(TAG, "accessibility service not running")
                        val success = service?.pressRecordedButton() ?: false
                        Log.d(TAG, "pressRecordedButton result=$success")
                        repo.acknowledgeCommand(
                            commandId = command.id,
                            success = success,
                            errorMessage = if (!success) "Accessibility service unavailable or button not found" else ""
                        )
                    }
                }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun buildNotification() = run {
        val channelId = "remote_tap_listener"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "RemoteTap Listener", NotificationManager.IMPORTANCE_LOW)
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, ServerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("RemoteTap")
            .setContentText("Listening for remote button commands")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "RemoteTap"
    }
}
