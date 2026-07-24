package com.erguotou.ezapp.feature.clipboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

enum class ClipboardConnectionState {
    OFF, CONNECTING, CONNECTED, ERROR,
}

data class ClipboardRuntimeState(
    val connection: ClipboardConnectionState = ClipboardConnectionState.OFF,
    val detail: String = "共享已关闭",
    val peerName: String? = null,
    val lastSyncedAt: Long? = null,
)

object ClipboardSyncRuntime {
    private val _state = MutableStateFlow(ClipboardRuntimeState())
    val state = _state.asStateFlow()

    private val lastRemoteHash = AtomicReference<Pair<String, Long>?>(null)

    fun update(state: ClipboardRuntimeState) {
        _state.value = state
    }

    fun markSynced(peerName: String?, detail: String) {
        _state.value = _state.value.copy(
            connection = ClipboardConnectionState.CONNECTED,
            detail = detail,
            peerName = peerName ?: _state.value.peerName,
            lastSyncedAt = System.currentTimeMillis(),
        )
    }

    fun markRemoteText(text: String) {
        lastRemoteHash.set(hash(text) to System.currentTimeMillis())
    }

    fun shouldIgnoreClipboard(text: String): Boolean {
        val marker = lastRemoteHash.get() ?: return false
        return marker.first == hash(text) && System.currentTimeMillis() - marker.second < 5_000
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
