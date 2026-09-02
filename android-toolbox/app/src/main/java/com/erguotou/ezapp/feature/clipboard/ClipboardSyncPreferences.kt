package com.erguotou.ezapp.feature.clipboard

import android.content.Context
import java.util.UUID

class ClipboardSyncPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) { preferences.edit().putBoolean(KEY_ENABLED, value).apply() }

    var trustedPeerKey: String?
        get() = preferences.getString(KEY_TRUSTED_PEER_KEY, null)
        set(value) { preferences.edit().putString(KEY_TRUSTED_PEER_KEY, value).apply() }

    var mqttUsername: String
        get() = preferences.getString(KEY_MQTT_USERNAME, "").orEmpty()
        set(value) { preferences.edit().putString(KEY_MQTT_USERNAME, value.trim()).apply() }

    var mqttPassword: String
        get() = preferences.getString(KEY_MQTT_PASSWORD, "").orEmpty()
        set(value) { preferences.edit().putString(KEY_MQTT_PASSWORD, value).apply() }

    var mqttHost: String
        get() = preferences.getString(KEY_MQTT_HOST, DEFAULT_MQTT_HOST)
            ?.ifBlank { DEFAULT_MQTT_HOST }
            ?: DEFAULT_MQTT_HOST
        set(value) {
            preferences.edit().putString(KEY_MQTT_HOST, value.trim()).apply()
        }

    var mqttPort: Int
        get() = preferences.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT)
            .takeIf { it in 1..65535 }
            ?: DEFAULT_MQTT_PORT
        set(value) {
            preferences.edit().putInt(KEY_MQTT_PORT, value).apply()
        }

    val mqttDeviceId: String
        get() {
            val existing = preferences.getString(KEY_MQTT_DEVICE_ID, null)
            if (existing != null) return existing
            return UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_MQTT_DEVICE_ID, it).apply()
            }
        }

    fun hasMqttConfig(): Boolean =
        mqttHost.isNotBlank() &&
            mqttPort in 1..65535 &&
            mqttUsername.isNotBlank() &&
            mqttPassword.isNotEmpty()

    companion object {
        const val DEFAULT_MQTT_HOST = "b01a87f3.ala.cn-hangzhou.emqxsl.cn"
        const val DEFAULT_MQTT_PORT = 8883
        private const val FILE_NAME = "clipboard_sync"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TRUSTED_PEER_KEY = "trusted_peer_key"
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_DEVICE_ID = "mqtt_device_id"
    }
}
