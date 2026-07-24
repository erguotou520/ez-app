package com.erguotou.ezapp.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.system.measureTimeMillis

data class SiteTarget(val name: String, val host: String, val url: String)

private val TARGETS = listOf(
    SiteTarget("百度", "baidu.com", "https://www.baidu.com/"),
    SiteTarget("Google", "google.com", "https://www.google.com/generate_204"),
    SiteTarget("YouTube", "youtube.com", "https://www.youtube.com/generate_204"),
    SiteTarget("GitHub", "github.com", "https://github.com/"),
)

data class SiteResult(
    val target: SiteTarget,
    val reachable: Boolean? = null,
    val latencyMs: Long? = null,
    val detail: String = "等待检测",
)

data class IpInfo(
    val ip: String,
    val location: String,
    val isp: String,
)

data class NetworkDiagnosticState(
    val running: Boolean = false,
    val sites: List<SiteResult> = TARGETS.map(::SiteResult),
    val ipInfo: IpInfo? = null,
    val ipError: String? = null,
)

class NetworkDiagnosticViewModel : ViewModel() {
    private val _state = MutableStateFlow(NetworkDiagnosticState())
    val state = _state.asStateFlow()

    init { diagnose() }

    fun diagnose() {
        if (_state.value.running) return
        _state.value = NetworkDiagnosticState(running = true)
        viewModelScope.launch {
            val siteTask = async(Dispatchers.IO) { TARGETS.map { async { checkSite(it) } }.awaitAll() }
            val ipTask = async(Dispatchers.IO) { loadIpInfo() }
            val sites = siteTask.await()
            val ipResult = ipTask.await()
            _state.value = NetworkDiagnosticState(
                sites = sites,
                ipInfo = ipResult.getOrNull(),
                ipError = ipResult.exceptionOrNull()?.toUserMessage(),
            )
        }
    }

    private fun checkSite(target: SiteTarget): SiteResult {
        var responseCode = 0
        return try {
            val elapsed = measureTimeMillis {
                val connection = (URL(target.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "EzApp-Network-Diagnostic/1.0")
                    setRequestProperty("Range", "bytes=0-0")
                }
                responseCode = connection.responseCode
                connection.disconnect()
            }
            SiteResult(target, reachable = responseCode in 100..599, latencyMs = elapsed, detail = "HTTP $responseCode")
        } catch (error: Exception) {
            SiteResult(target, reachable = false, detail = error.toUserMessage())
        }
    }

    private suspend fun loadIpInfo(): Result<IpInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("https://ipwho.is/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "EzApp-Network-Diagnostic/1.0")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            check(json.optBoolean("success", false)) { json.optString("message", "查询失败") }
            val location = listOf(json.optString("country"), json.optString("region"), json.optString("city"))
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase(Locale.ROOT) }
                .joinToString(" · ")
            val connectionInfo = json.optJSONObject("connection")
            IpInfo(
                ip = json.getString("ip"),
                location = location.ifBlank { "未知地区" },
                isp = connectionInfo?.optString("isp")?.takeIf { it.isNotBlank() }
                    ?: connectionInfo?.optString("org")?.takeIf { it.isNotBlank() }
                    ?: "未知网络服务商",
            )
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.UnknownHostException -> "域名无法解析"
        is javax.net.ssl.SSLException -> "安全连接失败"
        else -> message?.take(32) ?: "连接失败"
    }

    private companion object {
        const val TIMEOUT_MS = 6_000
    }
}
