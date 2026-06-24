package com.remotetap.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.remotetap.R
import com.remotetap.model.NotificationEvent
import com.remotetap.repository.CommandRepository
import com.remotetap.repository.PreferencesRepository
import com.remotetap.ui.ClientActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class NotificationRelayService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var prefs: PreferencesRepository

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        startRelaying()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun startRelaying() {
        val serverUrl = prefs.ntfyServerUrl
        val token = prefs.ntfyAccessToken
        val pairingCode = prefs.pairingCode
        if (serverUrl.isEmpty() || token.isEmpty() || pairingCode.isEmpty()) return

        val repo = CommandRepository(serverUrl, token, pairingCode)
        scope.launch {
            var delayMs = 1_000L
            while (true) {
                runCatching {
                    repo.observeNotifications().collect { event ->
                        delayMs = 1_000L
                        val ageMs = System.currentTimeMillis() - event.timestampMs
                        if (ageMs > 30_000L) return@collect
                        postForwardedNotification(event)
                    }
                }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun postForwardedNotification(event: NotificationEvent) {
        val channelId = "remote_tap_forwarded_notifs"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Forwarded Notifications", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, ClientActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle(event.title.ifEmpty { event.appName })
            .setContentText(event.text)
            .setSubText(event.appName)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(notifIdCounter.getAndIncrement(), notif)
    }

    private fun buildForegroundNotification() = run {
        val channelId = "remote_tap_relay_v1"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "RemoteTap Relay", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("RemoteTap Remote")
            .setContentText("Listening for forwarded notifications")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
            .also { it.flags = it.flags or android.app.Notification.FLAG_NO_CLEAR }
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1002
        private val notifIdCounter = AtomicInteger(10_000)
    }
}
