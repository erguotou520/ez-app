package org.fcitx.fcitx5.android.data.clipboard.sync

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.net.ssl.SSLSocketFactory

class ClipboardSyncMqttClient(
    private val preferences: ClipboardSyncPreferences,
    private val onMessage: (String) -> Unit,
    private val onState: (Boolean, String) -> Unit,
) {
    private var client: MqttAsyncClient? = null
    private var topic: String? = null

    @Synchronized
    fun start() {
        if (!preferences.hasMqttConfig()) {
            stop()
            onState(false, "MQTT 尚未配置")
            return
        }
        stop()
        runCatching {
            val username = preferences.mqttUsername
            val mqttTopic = ClipboardSyncCrypto.mqttTopic(username)
            val mqttClient = MqttAsyncClient(
                "ssl://${ClipboardSyncPreferences.MQTT_HOST}:${ClipboardSyncPreferences.MQTT_PORT}",
                "ez-ime-${preferences.mqttDeviceId}",
                MemoryPersistence(),
            )
            client = mqttClient
            topic = mqttTopic
            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (client !== mqttClient) return
                    runCatching { mqttClient.subscribe(mqttTopic, 1) }
                        .onSuccess { onState(true, "MQTT · 已连接") }
                        .onFailure { onState(false, "MQTT · 订阅失败") }
                }

                override fun connectionLost(cause: Throwable?) {
                    if (client === mqttClient) onState(false, "MQTT · 正在重连")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.payload?.toString(Charsets.UTF_8)?.let(onMessage)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                keepAliveInterval = 45
                connectionTimeout = 10
                userName = username
                password = preferences.mqttPassword.toCharArray()
                socketFactory = SSLSocketFactory.getDefault()
                isHttpsHostnameVerificationEnabled = true
            }
            mqttClient.connect(options)
        }.onFailure {
            stop()
            onState(false, "MQTT · 连接失败")
        }
    }

    fun publish(payload: String): Boolean {
        val mqttClient = client ?: return false
        val mqttTopic = topic ?: return false
        if (!mqttClient.isConnected) return false
        return runCatching {
            mqttClient.publish(
                mqttTopic,
                MqttMessage(payload.toByteArray()).apply {
                    qos = 1
                    isRetained = false
                },
            )
        }.isSuccess
    }

    @Synchronized
    fun stop() {
        client?.let {
            runCatching { if (it.isConnected) it.disconnectForcibly() }
            runCatching { it.close() }
        }
        client = null
        topic = null
    }
}
