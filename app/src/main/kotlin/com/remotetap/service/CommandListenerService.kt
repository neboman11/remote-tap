package com.remotetap.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.withContext

class CommandListenerService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var prefs: PreferencesRepository

    // Tracks the highest MMS _ID seen so far to avoid reprocessing old messages.
    private var lastProcessedMmsId = -1L

    private val mmsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            Log.i(TAG, "mmsObserver.onChange fired")
            scope.launch { checkMmsTrigger() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        startListening()
        startMmsMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(mmsObserver)
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
                        Log.i(TAG, "command received id=${command.id} ageMs=$ageMs")
                        // Discard commands queued while the phone was offline (ntfy caches 12h by default)
                        if (ageMs > 30_000L) {
                            Log.i(TAG, "discarding stale command (age=${ageMs}ms)")
                            return@collect
                        }
                        val service = RemoteTapAccessibilityService.instance
                        if (service == null) Log.w(TAG, "accessibility service not running")
                        val success = service?.pressRecordedButton() ?: false
                        Log.i(TAG, "pressRecordedButton result=$success")
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

    private fun startMmsMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "READ_SMS not granted, MMS/RCS trigger disabled")
            return
        }
        // Initialise to the current max MMS _ID so we only react to messages arriving
        // after this service starts, not messages already in the inbox.
        contentResolver.query(
            Telephony.Mms.Inbox.CONTENT_URI,
            arrayOf("MAX(${Telephony.Mms._ID})"),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) lastProcessedMmsId = cursor.getLong(0)
        }
        contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, mmsObserver)
        Log.i(TAG, "MMS/RCS observer registered, lastProcessedMmsId=$lastProcessedMmsId")
    }

    private suspend fun checkMmsTrigger() = withContext(Dispatchers.IO) {
        val trigger = prefs.smsTriggerMessage.trim()
        val contacts = prefs.smsContacts
        Log.i(TAG, "checkMmsTrigger: trigger='$trigger' contacts=${contacts.size} lastId=$lastProcessedMmsId")
        if (trigger.isEmpty() || contacts.isEmpty()) return@withContext

        contentResolver.query(
            Telephony.Mms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms._ID} > ?",
            arrayOf(lastProcessedMmsId.toString()),
            "${Telephony.Mms._ID} ASC"
        )?.use { cursor ->
            Log.i(TAG, "MMS inbox query returned ${cursor.count} rows")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                lastProcessedMmsId = id

                val body = getMmsBody(id) ?: continue
                Log.i(TAG, "MMS/RCS id=$id body=$body")
                if (!body.trim().equals(trigger, ignoreCase = true)) continue

                val sender = getMmsSender(id) ?: continue
                val matched = contacts.firstOrNull { numbersMatch(it.number, sender) }
                if (matched == null) {
                    Log.i(TAG, "MMS/RCS sender $sender not in allowlist: ${contacts.map { it.number }}")
                    continue
                }

                Log.i(TAG, "MMS/RCS trigger matched from ${matched.name}, pressing button")
                RemoteTapAccessibilityService.instance?.pressRecordedButton()
                break
            }
        }
    }

    private fun getMmsBody(mmsId: Long): String? =
        contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("text"),
            "ct = 'text/plain'",
            null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun getMmsSender(mmsId: Long): String? =
        contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address"),
            "type = 137", // PduHeaders.FROM
            null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun numbersMatch(stored: String, incoming: String): Boolean {
        val a = stored.filter { it.isDigit() }
        val b = incoming.filter { it.isDigit() }
        val minLen = minOf(a.length, b.length)
        if (minLen < 7) return false
        return a.endsWith(b) || b.endsWith(a)
    }

    private fun buildNotification() = run {
        val channelId = "remote_tap_listener_v2"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "RemoteTap Listener", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, ServerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("RemoteTap Host")
            .setContentText("Listening for remote button commands")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
            .also { it.flags = it.flags or android.app.Notification.FLAG_NO_CLEAR }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "RemoteTap"
    }
}
