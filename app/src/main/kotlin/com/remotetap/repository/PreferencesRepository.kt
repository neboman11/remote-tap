package com.remotetap.repository

import android.content.Context
import com.google.gson.Gson
import com.remotetap.model.ButtonConfig

private const val PREFS_NAME = "remote_tap_prefs"
private const val KEY_ROLE = "role"
private const val KEY_PAIRING_CODE = "pairing_code"
private const val KEY_BUTTON_CONFIG = "button_config"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_NTFY_SERVER_URL = "ntfy_server_url"
private const val KEY_NTFY_ACCESS_TOKEN = "ntfy_access_token"

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

    fun isNtfyConfigured() = ntfyServerUrl.isNotEmpty() && ntfyAccessToken.isNotEmpty()

    fun clearAll() = prefs.edit().clear().apply()
}
