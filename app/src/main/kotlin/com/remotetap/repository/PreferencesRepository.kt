package com.remotetap.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.remotetap.model.ButtonConfig
import com.remotetap.model.SmsContact

private const val PREFS_NAME = "remote_tap_prefs"
private const val KEY_ROLE = "role"
private const val KEY_PAIRING_CODE = "pairing_code"
private const val KEY_BUTTON_CONFIG = "button_config"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_NTFY_SERVER_URL = "ntfy_server_url"
private const val KEY_NTFY_ACCESS_TOKEN = "ntfy_access_token"
private const val KEY_WATCHED_PACKAGE = "watched_package"
private const val KEY_WATCHED_USER_SERIAL = "watched_user_serial"
private const val KEY_SMS_TRIGGER_MESSAGE = "sms_trigger_message"
private const val KEY_SMS_CONTACTS = "sms_contacts"

enum class DeviceRole { NONE, SERVER, CLIENT }

class PreferencesRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    var role: DeviceRole
        get() = DeviceRole.valueOf(prefs.getString(KEY_ROLE, DeviceRole.NONE.name)!!)
        set(value) = prefs.edit().putString(KEY_ROLE, value.name).apply()

    var pairingCode: String
        get() = prefs.getString(KEY_PAIRING_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRING_CODE, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var ntfyServerUrl: String
        get() = prefs.getString(KEY_NTFY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NTFY_SERVER_URL, value.trimEnd('/')).apply()

    var ntfyAccessToken: String
        get() = prefs.getString(KEY_NTFY_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NTFY_ACCESS_TOKEN, value).apply()

    var buttonConfig: ButtonConfig?
        get() {
            val json = prefs.getString(KEY_BUTTON_CONFIG, null) ?: return null
            return gson.fromJson(json, ButtonConfig::class.java)
        }
        set(value) {
            if (value == null) prefs.edit().remove(KEY_BUTTON_CONFIG).apply()
            else prefs.edit().putString(KEY_BUTTON_CONFIG, gson.toJson(value)).apply()
        }

    var watchedPackageName: String
        get() = prefs.getString(KEY_WATCHED_PACKAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WATCHED_PACKAGE, value).apply()

    // -1 means unset; otherwise a UserManager serial number identifying the profile to monitor
    var watchedUserSerial: Long
        get() = prefs.getLong(KEY_WATCHED_USER_SERIAL, -1L)
        set(value) = prefs.edit().putLong(KEY_WATCHED_USER_SERIAL, value).apply()

    var smsTriggerMessage: String
        get() = prefs.getString(KEY_SMS_TRIGGER_MESSAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMS_TRIGGER_MESSAGE, value).apply()

    var smsContacts: List<SmsContact>
        get() {
            val json = prefs.getString(KEY_SMS_CONTACTS, null) ?: return emptyList()
            val type = object : TypeToken<List<SmsContact>>() {}.type
            return runCatching { gson.fromJson<List<SmsContact>>(json, type) }.getOrDefault(emptyList())
        }
        set(value) = prefs.edit().putString(KEY_SMS_CONTACTS, gson.toJson(value)).apply()

    fun isNtfyConfigured() = ntfyServerUrl.isNotEmpty() && ntfyAccessToken.isNotEmpty()

    fun clearAll() = prefs.edit().clear().apply()
}
