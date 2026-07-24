package com.erguotou.ezapp.feature.clipboard

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import android.os.Handler
import android.os.Looper
import javax.net.ssl.SSLSocketFactory

class ClipboardMqttClient(
    private val preferences: ClipboardSyncPreferences,
    private val onMessage: (String) -> Unit,
    private val onState: (Boolean, String) -> Unit,
) {
    private var client: MqttAsyncClient? = null
    private var topic: String? = null
    private var activeSignature: String? = null
    private var generation = 0
    private val retryHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun start() {
        if (!preferences.hasMqttConfig()) {
            stop()
            onState(false, "MQTT 尚未配置")
            return
        }

        val username = preferences.mqttUsername
        val signature = "$username\u0000${preferences.mqttPassword}"
        if (activeSignature == signature && client != null) return
        stopLocked()
        activeSignature = signature
        val currentGeneration = generation
        val mqttTopic = ClipboardCrypto.mqttTopic(username)
        topic = mqttTopic
        val mqttClient = MqttAsyncClient(
            "ssl://${ClipboardSyncPreferences.MQTT_HOST}:${ClipboardSyncPreferences.MQTT_PORT}",
            "ez-android-${preferences.mqttDeviceId}",
            MemoryPersistence(),
        )
        client = mqttClient
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (client !== mqttClient || generation != currentGeneration) return
                runCatching { mqttClient.subscribe(mqttTopic, 1) }
                onState(true, "MQTT · 远程通道已连接")
            }

            override fun connectionLost(cause: Throwable?) {
                if (client === mqttClient && generation == currentGeneration) {
                    onState(false, "MQTT · 远程通道正在重连")
                }
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
        runCatching {
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    synchronized(this@ClipboardMqttClient) {
                        if (client !== mqttClient || generation != currentGeneration) return
                        runCatching { mqttClient.close() }
                        client = null
                        topic = null
                    }
                    onState(false, "MQTT · 连接失败，正在重试")
                    retryHandler.postDelayed({
                        synchronized(this@ClipboardMqttClient) {
                            if (generation == currentGeneration) {
                                activeSignature = null
                                start()
                            }
                        }
                    }, RETRY_DELAY_MS)
                }
            })
        }.onFailure {
            onState(false, "MQTT · 连接失败，正在重试")
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
        stopLocked()
    }

    private fun stopLocked() {
        generation++
        activeSignature = null
        client?.let {
            runCatching { if (it.isConnected) it.disconnectForcibly() }
            runCatching { it.close() }
        }
        client = null
        topic = null
    }

    companion object {
        private const val RETRY_DELAY_MS = 3_000L
    }
}
