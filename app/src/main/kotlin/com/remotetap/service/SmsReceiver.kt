package com.remotetap.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.remotetap.repository.PreferencesRepository

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val prefs = PreferencesRepository(context)
        val triggerMessage = prefs.smsTriggerMessage.trim()
        val contacts = prefs.smsContacts
        if (triggerMessage.isEmpty() || contacts.isEmpty()) return

        Log.i(TAG, "SMS received: ${messages.size} part(s), ${contacts.size} allowed contact(s)")

        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: continue
            val body = sms.messageBody?.trim() ?: continue
            Log.i(TAG, "SMS from=$sender body=$body trigger=$triggerMessage")
            if (!body.equals(triggerMessage, ignoreCase = true)) continue
            val matched = contacts.firstOrNull { numbersMatch(it.number, sender) }
            if (matched == null) {
                Log.i(TAG, "sender $sender did not match any of: ${contacts.map { it.number }}")
                continue
            }

            Log.i(TAG, "SMS trigger matched (${matched.name}), pressing button")
            val pendingResult = goAsync()
            Thread {
                try {
                    val pressed = RemoteTapAccessibilityService.instance?.pressRecordedButton()
                    Log.i(TAG, "SMS-triggered button press result=$pressed")
                } finally {
                    pendingResult.finish()
                }
            }.start()
            break
        }
    }

    // Compares phone numbers independent of country-code prefix and formatting by checking
    // whether one number's digit string is a suffix of the other's. This handles the common
    // mismatch where a contact is stored as "+15551234567" but the SMS sender arrives as
    // "5551234567" (or vice versa).
    private fun numbersMatch(stored: String, incoming: String): Boolean {
        val a = stored.filter { it.isDigit() }
        val b = incoming.filter { it.isDigit() }
        val minLen = minOf(a.length, b.length)
        if (minLen < 7) return false
        return a.endsWith(b) || b.endsWith(a)
    }

    companion object {
        private const val TAG = "RemoteTap"
    }
}
