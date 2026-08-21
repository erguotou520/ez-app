package org.fcitx.fcitx5.android.data.clipboard.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber
import javax.net.ssl.SSLSocketFactory

class ClipboardSyncMqttClient(
    private val preferences: ClipboardSyncPreferences,
    private val onMessage: (String) -> Unit,
    private val onState: (Boolean, String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: MqttAsyncClient? = null
    private var topic: String? = null
    private var connectOptions: MqttConnectOptions? = null
    private var reconnectJob: Job? = null
    @Volatile private var stopped = true

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
                "ssl://${preferences.mqttHost}:${preferences.mqttPort}",
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
                    if (client !== mqttClient) return
                    if (stopped) return
                    onState(false, "MQTT · 正在重连")
                    // 禁用 paho 内部自动重连（其 TimerPingSender 线程在 close/disconnect
                    // 竞态下会触发 NPE 崩溃），改为在协程中安全重连
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.payload?.toString(Charsets.UTF_8)?.let(onMessage)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = false
                isCleanSession = true
                keepAliveInterval = 45
                connectionTimeout = 10
                userName = username
                password = preferences.mqttPassword.toCharArray()
                socketFactory = SSLSocketFactory.getDefault()
                isHttpsHostnameVerificationEnabled = true
            }
            connectOptions = options
            stopped = false
            mqttClient.connect(options)
        }.onFailure {
            stopped = true
            stop()
            onState(false, "MQTT · 连接失败")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            val mqttClient = client ?: return@launch
            val options = connectOptions ?: return@launch
            if (stopped || mqttClient.isConnected) return@launch
            runCatching { mqttClient.connect(options) }
                .onFailure {
                    if (!stopped) {
                        Timber.w("MQTT reconnect failed: $it")
                        scheduleReconnect()
                    }
                }
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
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        client?.let {
            runCatching { if (it.isConnected) it.disconnectForcibly() }
            runCatching { it.close() }
        }
        client = null
        topic = null
        connectOptions = null
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 3_000L
    }
}