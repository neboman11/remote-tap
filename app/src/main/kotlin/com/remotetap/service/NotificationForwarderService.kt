package com.remotetap.service

import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.remotetap.model.NotificationEvent
import com.remotetap.repository.CommandRepository
import com.remotetap.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class NotificationForwarderService : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var prefs: PreferencesRepository

    // key = sbn.key, value = "$title|$text" last forwarded for that notification
    private val lastForwarded = ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastForwarded.remove(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val watchedPkg = prefs.watchedPackageName
        if (watchedPkg.isEmpty() || sbn.packageName != watchedPkg) return
        val watchedSerial = prefs.watchedUserSerial
        if (watchedSerial != -1L) {
            val um = getSystemService(UserManager::class.java)
            if (um.getSerialNumberForUser(sbn.user) != watchedSerial) return
        }

        // Group summary notifications (e.g. "3 new messages") duplicate the individual ones
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        // Skip if this exact content was already forwarded for this notification slot
        val contentKey = "$title|$text"
        if (lastForwarded[sbn.key] == contentKey) return
        lastForwarded[sbn.key] = contentKey

        val appName = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        val serverUrl = prefs.ntfyServerUrl
        val token = prefs.ntfyAccessToken
        val pairingCode = prefs.pairingCode
        if (serverUrl.isEmpty() || token.isEmpty() || pairingCode.isEmpty()) return

        val event = NotificationEvent(
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            timestampMs = System.currentTimeMillis()
        )
        val repo = CommandRepository(serverUrl, token, pairingCode)
        scope.launch { runCatching { repo.publishNotification(event) } }
    }
}
