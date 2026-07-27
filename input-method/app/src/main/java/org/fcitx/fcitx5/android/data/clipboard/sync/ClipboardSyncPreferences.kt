package org.fcitx.fcitx5.android.data.clipboard.sync

import android.content.Context
import java.util.UUID

class ClipboardSyncPreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    var mqttUsername: String
        get() = preferences.getString(KEY_MQTT_USERNAME, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_MQTT_USERNAME, value.trim()).apply()

    var mqttPassword: String
        get() = preferences.getString(KEY_MQTT_PASSWORD, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_MQTT_PASSWORD, value).apply()

    var trustedPeerKey: String?
        get() = preferences.getString(KEY_TRUSTED_PEER_KEY, null)
        set(value) = preferences.edit().putString(KEY_TRUSTED_PEER_KEY, value).apply()

    val mqttDeviceId: String
        get() {
            val current = preferences.getString(KEY_MQTT_DEVICE_ID, null)
            if (current != null) return current
            return UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_MQTT_DEVICE_ID, it).apply()
            }
        }

    fun hasMqttConfig() = mqttUsername.isNotBlank() && mqttPassword.isNotEmpty()

    companion object {
        const val MQTT_HOST = "b01a87f3.ala.cn-hangzhou.emqxsl.cn"
        const val MQTT_PORT = 8883

        private const val FILE_NAME = "ez_clipboard_sync"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_DEVICE_ID = "mqtt_device_id"
        private const val KEY_TRUSTED_PEER_KEY = "trusted_peer_key"
    }
}
