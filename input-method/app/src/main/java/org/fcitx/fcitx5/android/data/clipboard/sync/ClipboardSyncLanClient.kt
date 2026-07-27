package org.fcitx.fcitx5.android.data.clipboard.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class ClipboardSyncLanClient(
    context: Context,
    private val preferences: ClipboardSyncPreferences,
    private val onMessage: (JSONObject, ByteArray) -> Unit,
    private val onState: (Boolean, String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val writerLock = Any()
    private var connectionJob: Job? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var activeSocket: Socket? = null

    fun start() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            var retryMs = 1_000L
            while (isActive && preferences.enabled) {
                runCatching {
                    onState(false, "正在寻找同一 Wi-Fi 下的电脑")
                    val service = discoverPeer()
                    Socket().use { socket ->
                        activeSocket = socket
                        socket.keepAlive = true
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(service.host, service.port), 5_000)
                        socket.soTimeout = 70_000
                        val socketWriter =
                            BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val key = establishSecureSession(socketWriter, reader)
                        writer = socketWriter
                        sessionKey = key
                        retryMs = 1_000L
                        onState(true, "局域网 · 已加密连接")
                        while (isActive && preferences.enabled) {
                            val message = JSONObject(reader.readLine() ?: error("电脑已断开连接"))
                            when (message.optString("type")) {
                                "clip" -> onMessage(message, key)
                                "ping" -> writeLine(
                                    socketWriter,
                                    JSONObject().put("type", "pong").toString(),
                                )
                            }
                        }
                    }
                }.onFailure {
                    activeSocket = null
                    writer = null
                    sessionKey = null
                    if (preferences.enabled) {
                        onState(
                            false,
                            "局域网 · ${it.message?.take(48) ?: "连接失败"}",
                        )
                    }
                }
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun publish(text: String, updatedAt: Long, id: String): Boolean {
        val target = writer ?: return false
        val key = sessionKey ?: return false
        return runCatching {
            writeLine(target, ClipboardSyncCrypto.encrypt(key, text, updatedAt, "", id))
        }.isSuccess
    }

    fun stop() {
        stopDiscovery()
        connectionJob?.cancel()
        connectionJob = null
        runCatching { activeSocket?.close() }
        activeSocket = null
        writer = null
        sessionKey = null
    }

    private suspend fun discoverPeer(): NsdServiceInfo {
        val result = CompletableDeferred<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                result.completeExceptionally(
                    IllegalStateException("无法启动电脑发现（错误 $errorCode）")
                )
                stopDiscovery()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains(SERVICE_TYPE)) return
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (result.complete(serviceInfo)) stopDiscovery()
                    }
                })
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

    private fun establishSecureSession(
        socketWriter: BufferedWriter,
        reader: BufferedReader,
    ): ByteArray {
        val keyPair = ClipboardSyncCrypto.createKeyPair()
        writeLine(
            socketWriter,
            JSONObject()
                .put("type", "hello")
                .put("device", android.os.Build.MODEL)
                .put("publicKey", ClipboardSyncCrypto.publicKeyBase64(keyPair))
                .toString(),
        )
        val response = JSONObject(reader.readLine() ?: error("电脑未响应"))
        check(response.optString("type") == "hello_ack") { "电脑拒绝连接" }
        val peerKey = response.getString("publicKey")
        val trustedKey = preferences.trustedPeerKey
        if (trustedKey != null && trustedKey != peerKey) {
            error("电脑身份已变化")
        }
        if (trustedKey == null) preferences.trustedPeerKey = peerKey
        return ClipboardSyncCrypto.deriveSessionKey(keyPair, peerKey)
    }

    private fun writeLine(target: BufferedWriter, value: String) {
        synchronized(writerLock) {
            target.write(value)
            target.newLine()
            target.flush()
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_ezclip._tcp."
    }
}
