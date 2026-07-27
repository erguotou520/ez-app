package org.fcitx.fcitx5.android.data.clipboard.sync

import android.content.ClipData
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.json.JSONObject
import timber.log.Timber
import java.util.LinkedHashSet
import java.util.UUID

object ClipboardSyncRuntime {
    private const val MAX_TEXT_BYTES = 256 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preferences: ClipboardSyncPreferences? = null
    private var mqttClient: ClipboardSyncMqttClient? = null
    private var lanClient: ClipboardSyncLanClient? = null
    private var latestClipboardAt = 0L
    private var remoteText: String? = null
    private var mqttConnected = false
    private var lanConnected = false
    private val processedMessageIds = LinkedHashSet<String>()

    @Volatile
    var state: String = "同步已关闭"
        private set

    private fun updateState(detail: String) {
        state = detail
        Timber.i("Clipboard sync: $detail")
    }

    private val clipboardListener = ClipboardManager.OnClipboardUpdateListener(::send)

    @Synchronized
    fun start(context: Context) {
        val prefs = preferences ?: ClipboardSyncPreferences(context).also { preferences = it }
        if (!prefs.enabled) {
            stop()
            updateState("同步已关闭")
            return
        }
        if (mqttClient != null) return
        ClipboardManager.addOnUpdateListener(clipboardListener)
        mqttClient = ClipboardSyncMqttClient(prefs, ::receiveMqtt) { connected, detail ->
            mqttConnected = connected
            if (!lanConnected) updateState(detail)
        }
        lanClient = ClipboardSyncLanClient(context, prefs, ::receiveLan) { connected, detail ->
            lanConnected = connected
            updateState(detail)
            if (connected) {
                mqttConnected = false
                mqttClient?.stop()
            } else if (prefs.enabled) {
                mqttClient?.start()
            }
        }.also { it.start() }
        updateState("正在寻找同一 Wi-Fi 下的电脑")
        scope.launch {
            delay(2_000)
            if (!lanConnected && prefs.enabled && prefs.hasMqttConfig()) mqttClient?.start()
        }
    }

    @Synchronized
    fun restart(context: Context) {
        stop()
        start(context)
    }

    @Synchronized
    fun stop() {
        ClipboardManager.removeOnUpdateListener(clipboardListener)
        mqttClient?.stop()
        mqttClient = null
        lanClient?.stop()
        lanClient = null
        mqttConnected = false
        lanConnected = false
    }

    private fun send(entry: ClipboardEntry) {
        val prefs = preferences ?: return
        val text = entry.text
        if (text == remoteText) {
            remoteText = null
            return
        }
        if (text.isBlank() || text.toByteArray().size > MAX_TEXT_BYTES) return
        val updatedAt = entry.timestamp.coerceAtLeast(System.currentTimeMillis())
        if (updatedAt <= latestClipboardAt) return
        val messageId = UUID.randomUUID().toString()
        val sentByLan = lanClient?.publish(text, updatedAt, messageId) == true
        val sentByMqtt = if (!sentByLan && mqttConnected) {
            mqttClient?.publish(
                ClipboardSyncCrypto.encrypt(
                    ClipboardSyncCrypto.mqttKey(prefs.mqttUsername, prefs.mqttPassword),
                    text,
                    updatedAt,
                    prefs.mqttDeviceId,
                    messageId,
                )
            ) == true
        } else {
            false
        }
        if (sentByLan || sentByMqtt) {
            latestClipboardAt = updatedAt
            rememberMessageId(messageId)
            updateState(if (sentByLan) {
                "局域网 · 已同步剪切板"
            } else {
                "MQTT · 已同步剪切板"
            })
        }
    }

    private fun receiveMqtt(payload: String) {
        val prefs = preferences ?: return
        scope.launch {
            runCatching {
                val message = JSONObject(payload)
                if (message.optString("type") != "clip" ||
                    message.optString("senderId") == prefs.mqttDeviceId
                ) return@runCatching
                val text = ClipboardSyncCrypto.decrypt(
                    ClipboardSyncCrypto.mqttKey(prefs.mqttUsername, prefs.mqttPassword),
                    message,
                )
                applyRemote(message, text, "MQTT")
            }.onFailure {
                Timber.w(it, "Failed to receive synchronized clipboard")
                updateState("MQTT · 收到无法解密的内容")
            }
        }
    }

    private fun receiveLan(message: JSONObject, key: ByteArray) {
        scope.launch {
            runCatching {
                applyRemote(message, ClipboardSyncCrypto.decrypt(key, message), "局域网")
            }.onFailure {
                Timber.w(it, "Failed to receive LAN clipboard")
                updateState("局域网 · 收到无法解密的内容")
            }
        }
    }

    private fun applyRemote(message: JSONObject, text: String, channel: String) {
        val id = message.optString("id")
        val updatedAt = message.optLong("updatedAt")
        if (id.isBlank() ||
            id in processedMessageIds ||
            updatedAt <= latestClipboardAt ||
            text.isBlank() ||
            text.toByteArray().size > MAX_TEXT_BYTES
        ) return
        rememberMessageId(id)
        latestClipboardAt = updatedAt
        remoteText = text
        val context = org.fcitx.fcitx5.android.FcitxApplication.getInstance()
        context.clipboardManager.setPrimaryClip(
            ClipData.newPlainText("来自同步剪切板", text)
        )
        updateState("$channel · 已接收同步剪切板")
    }

    @Synchronized
    private fun rememberMessageId(id: String) {
        processedMessageIds += id
        while (processedMessageIds.size > 256) {
            processedMessageIds.remove(processedMessageIds.first())
        }
    }
}
