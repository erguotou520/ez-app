package com.erguotou.ezapp.feature.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.erguotou.ezapp.MainActivity
import com.erguotou.ezapp.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.LinkedHashSet
import java.util.UUID

class ClipboardSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerLock = Any()
    private lateinit var preferences: ClipboardSyncPreferences
    private lateinit var historyStore: ClipboardHistoryStore
    private lateinit var nsdManager: NsdManager
    private lateinit var mqttClient: ClipboardMqttClient
    private var connectionJob: Job? = null
    private var mqttFallbackJob: Job? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var peerName: String? = null
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var latestClipboardAt: Long = 0L
    @Volatile private var mqttConnected = false
    private val processedMessageIds = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        preferences = ClipboardSyncPreferences(this)
        historyStore = ClipboardHistoryStore(this)
        nsdManager = getSystemService(NsdManager::class.java)
        mqttClient = ClipboardMqttClient(
            preferences = preferences,
            onMessage = ::receiveMqttMessage,
            onState = ::updateMqttState,
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                preferences.enabled = false
                stopSync()
                return START_NOT_STICKY
            }
            ACTION_SEND -> {
                intent.getStringExtra(EXTRA_TEXT)?.takeIf(String::isNotEmpty)?.let(::sendText)
                return START_NOT_STICKY
            }
        }
        if (!preferences.enabled) {
            stopSync()
            return START_NOT_STICKY
        }
        showForegroundNotification("正在寻找可用的同步通道…")
        if (connectionJob?.isActive != true) {
            connectionJob = scope.launch { connectionLoop() }
        }
        startMqttFallback(MQTT_FALLBACK_DELAY_MS)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopDiscovery()
        mqttClient.stop()
        mqttFallbackJob?.cancel()
        clearConnection()
        connectionJob?.cancel()
        scope.cancel()
        if (!preferences.enabled) ClipboardSyncRuntime.update(ClipboardRuntimeState())
        super.onDestroy()
    }

    private suspend fun connectionLoop() {
        var retryDelay = 1_000L
        while (scope.isActive && preferences.enabled) {
            if (!mqttConnected) {
                ClipboardSyncRuntime.update(
                    ClipboardRuntimeState(
                        connection = ClipboardConnectionState.CONNECTING,
                        detail = "正在自动寻找同一 Wi-Fi 下的电脑",
                    )
                )
                updateNotification("正在寻找附近的电脑…")
            }
            try {
                val service = discoverPeer()
                Socket().use { socket ->
                    socket.keepAlive = true
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(service.host, service.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    val socketWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    establishSecureSession(socketWriter, reader)
                    writer = socketWriter
                    mqttFallbackJob?.cancel()
                    mqttConnected = false
                    mqttClient.stop()
                    retryDelay = 1_000L
                    ClipboardSyncRuntime.update(
                        ClipboardRuntimeState(
                            connection = ClipboardConnectionState.CONNECTED,
                            detail = "局域网 · 已加密连接",
                            peerName = peerName,
                        )
                    )
                    updateNotification("局域网 · 已连接 ${peerName ?: service.serviceName}")
                    readMessages(reader, socketWriter)
                }
            } catch (error: Exception) {
                clearConnection()
                if (!preferences.enabled) break
                startMqttFallback(0L)
                val detail = when (error) {
                    is SocketTimeoutException -> "连接超时，正在重新寻找"
                    is SecurityException -> error.message ?: "电脑身份验证失败"
                    else -> error.message?.take(56) ?: "暂未发现电脑"
                }
                if (!mqttConnected) {
                    ClipboardSyncRuntime.update(
                        ClipboardRuntimeState(ClipboardConnectionState.ERROR, detail)
                    )
                    updateNotification(detail)
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private suspend fun discoverPeer(): NsdServiceInfo {
        val result = CompletableDeferred<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                result.completeExceptionally(IllegalStateException("无法启动电脑发现"))
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains(SERVICE_TYPE)) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (result.complete(serviceInfo)) stopDiscovery()
                        }
                    })
                }
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        result.invokeOnCompletion { stopDiscovery() }
        return result.await()
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
    }

    private fun establishSecureSession(socketWriter: BufferedWriter, reader: BufferedReader) {
        val keyPair = ClipboardCrypto.createKeyPair()
        writeLine(
            socketWriter,
            JSONObject()
                .put("type", "hello")
                .put("device", android.os.Build.MODEL)
                .put("publicKey", ClipboardCrypto.publicKeyBase64(keyPair))
                .toString(),
        )
        val response = JSONObject(reader.readLine() ?: error("电脑未响应"))
        check(response.optString("type") == "hello_ack") { "电脑拒绝连接" }
        val peerKey = response.getString("publicKey")
        val trustedKey = preferences.trustedPeerKey
        if (trustedKey != null && trustedKey != peerKey) {
            throw SecurityException("电脑身份已变化，请重新配对")
        }
        if (trustedKey == null) preferences.trustedPeerKey = peerKey
        sessionKey = ClipboardCrypto.deriveSessionKey(keyPair, peerKey)
        peerName = response.optString("device").takeIf(String::isNotBlank)
    }

    private fun readMessages(reader: BufferedReader, socketWriter: BufferedWriter) {
        while (scope.isActive && preferences.enabled) {
            val message = JSONObject(reader.readLine() ?: error("电脑已断开连接"))
            when (message.optString("type")) {
                "clip" -> {
                    val updatedAt = message.optLong("updatedAt", 0L)
                    if (updatedAt <= latestClipboardAt) continue
                    val key = sessionKey ?: error("安全连接未建立")
                    val text = ClipboardCrypto.decryptText(key, message)
                    if (text.toByteArray().size <= MAX_TEXT_BYTES) {
                        applyRemoteText(
                            message.optString("id"),
                            text,
                            updatedAt,
                            peerName ?: "电脑",
                        )
                    }
                }
                "ping" -> writeLine(socketWriter, JSONObject().put("type", "pong").toString())
            }
        }
    }

    private fun sendText(text: String) {
        if (text.toByteArray().size > MAX_TEXT_BYTES) {
            ClipboardSyncRuntime.update(
                ClipboardSyncRuntime.state.value.copy(detail = "文本超过 256 KB，未同步")
            )
            return
        }
        scope.launch {
            val activeWriter = writer
            val activeKey = sessionKey
            val updatedAt = System.currentTimeMillis()
            val messageId = UUID.randomUUID().toString()
            var sentByLan = false
            if (activeWriter != null && activeKey != null) {
                sentByLan = runCatching {
                    writeLine(
                        activeWriter,
                        ClipboardCrypto.encryptText(activeKey, text, updatedAt, messageId),
                    )
                }.isSuccess
                if (!sentByLan) clearConnection()
            }
            val sentByMqtt = if (!sentByLan && mqttConnected && preferences.hasMqttConfig()) {
                mqttClient.publish(
                    ClipboardCrypto.encryptText(
                        ClipboardCrypto.mqttKey(
                            preferences.mqttUsername,
                            preferences.mqttPassword,
                        ),
                        text,
                        updatedAt,
                        messageId,
                        preferences.mqttDeviceId,
                    )
                )
            } else {
                false
            }
            if (sentByLan || sentByMqtt) {
                rememberMessageId(messageId)
                latestClipboardAt = maxOf(latestClipboardAt, updatedAt)
                historyStore.add(messageId, text, updatedAt)
                val detail = if (sentByMqtt) {
                    "MQTT · 已同步剪切板"
                } else {
                    "局域网 · 已同步到 ${peerName ?: "电脑"}"
                }
                ClipboardSyncRuntime.markSynced(peerName, detail)
                updateNotification(detail)
            } else {
                ClipboardSyncRuntime.update(
                    ClipboardSyncRuntime.state.value.copy(detail = "暂无可用同步通道")
                )
            }
        }
    }

    private fun receiveMqttMessage(payload: String) {
        scope.launch {
            runCatching {
                val message = JSONObject(payload)
                val id = message.optString("id")
                val updatedAt = message.optLong("updatedAt", 0L)
                if (message.optString("senderId") == preferences.mqttDeviceId ||
                    id.isBlank() ||
                    hasProcessedMessageId(id) ||
                    updatedAt <= latestClipboardAt
                ) return@runCatching
                val text = ClipboardCrypto.decryptText(
                    ClipboardCrypto.mqttKey(
                        preferences.mqttUsername,
                        preferences.mqttPassword,
                    ),
                    message,
                )
                if (text.toByteArray().size <= MAX_TEXT_BYTES) {
                    applyRemoteText(id, text, updatedAt, "另一台设备")
                }
            }.onFailure {
                updateMqttState(mqttConnected, "收到无法解密的远程内容")
            }
        }
    }

    private fun applyRemoteText(id: String, text: String, updatedAt: Long, source: String) {
        if (updatedAt <= latestClipboardAt || hasProcessedMessageId(id)) return
        rememberMessageId(id)
        latestClipboardAt = updatedAt
        historyStore.add(id, text, updatedAt)
        ClipboardSyncRuntime.markRemoteText(text)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("来自 $source", text)
        )
        val channel = if (source == "另一台设备") "MQTT" else "局域网"
        ClipboardSyncRuntime.markSynced(peerName, "$channel · 已接收同步剪切板")
        updateNotification("$channel · 刚刚收到来自 $source 的内容")
    }

    @Synchronized
    private fun hasProcessedMessageId(id: String) = id in processedMessageIds

    @Synchronized
    private fun rememberMessageId(id: String) {
        processedMessageIds += id
        while (processedMessageIds.size > 256) {
            processedMessageIds.remove(processedMessageIds.first())
        }
    }

    private fun updateMqttState(connected: Boolean, detail: String) {
        Log.i(LOG_TAG, detail)
        if (writer != null) {
            if (connected) mqttClient.stop()
            return
        }
        mqttConnected = connected
        ClipboardSyncRuntime.update(
            ClipboardRuntimeState(
                connection = if (connected) {
                    ClipboardConnectionState.CONNECTED
                } else {
                    ClipboardConnectionState.CONNECTING
                },
                detail = detail,
            )
        )
        updateNotification(detail)
    }

    private fun startMqttFallback(delayMs: Long) {
        if (writer != null || !preferences.hasMqttConfig()) return
        if (mqttConnected) return
        mqttFallbackJob?.cancel()
        mqttFallbackJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (preferences.enabled && writer == null) {
                updateMqttState(false, "MQTT · 正在连接，同时寻找局域网")
                mqttClient.start()
            }
        }
    }

    private fun writeLine(target: BufferedWriter, value: String) {
        synchronized(writerLock) {
            target.write(value)
            target.newLine()
            target.flush()
        }
    }

    private fun clearConnection() {
        writer = null
        sessionKey = null
        peerName = null
    }

    private fun stopSync() {
        stopDiscovery()
        mqttClient.stop()
        mqttFallbackJob?.cancel()
        clearConnection()
        connectionJob?.cancel()
        ClipboardSyncRuntime.update(ClipboardRuntimeState())
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showForegroundNotification(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(text),
        )
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clipboard_sync)
            .setContentTitle("剪切板共享已开启")
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .addAction(
                0, "关闭共享",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, ClipboardSyncService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "剪切板共享",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示手机与电脑剪切板连接状态"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val SERVICE_TYPE = "_ezclip._tcp."
        private const val CHANNEL_ID = "clipboard_sync"
        private const val NOTIFICATION_ID = 1203
        private const val ACTION_START = "clipboard.start"
        private const val ACTION_STOP = "clipboard.stop"
        private const val ACTION_SEND = "clipboard.send"
        private const val EXTRA_TEXT = "text"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 70_000
        private const val MAX_RETRY_MS = 30_000L
        private const val MQTT_FALLBACK_DELAY_MS = 2_000L
        private const val MAX_TEXT_BYTES = 256 * 1024
        private const val LOG_TAG = "EzClipboardSync"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClipboardSyncService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ClipboardSyncService::class.java).setAction(ACTION_STOP),
            )
        }

        fun send(context: Context, text: String) {
            context.startService(
                Intent(context, ClipboardSyncService::class.java)
                    .setAction(ACTION_SEND)
                    .putExtra(EXTRA_TEXT, text),
            )
        }
    }
}
