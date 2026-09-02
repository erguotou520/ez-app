package com.erguotou.ezapp.feature.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.erguotou.ezapp.ui.components.AppScreen
import com.erguotou.ezapp.ui.theme.Ink
import com.erguotou.ezapp.ui.theme.MutedInk
import com.erguotou.ezapp.ui.theme.Paper
import com.erguotou.ezapp.ui.theme.SignalGreen
import com.erguotou.ezapp.ui.theme.SignalOrange
import com.erguotou.ezapp.ui.theme.SignalYellow
import com.erguotou.ezapp.ui.theme.SoftLine
import com.erguotou.ezapp.ui.theme.Vermilion

@Composable
fun NetworkDiagnosticScreen(
    onBack: () -> Unit,
    viewModel: NetworkDiagnosticViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    AppScreen(background = Paper) {
        Column(Modifier.fillMaxSize()) {
            NetworkTopBar(onBack = onBack, running = state.running, onRefresh = viewModel::diagnose)
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(18.dp))
                Text("当前出口", color = MutedInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                IpSection(state, onCopy = { ip ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("公网 IP", ip))
                    Toast.makeText(context, "IP 已复制", Toast.LENGTH_SHORT).show()
                })
                Spacer(Modifier.height(38.dp))
                Text("站点连通性", color = MutedInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                state.sites.forEach { SiteRow(it) }
                Spacer(Modifier.weight(1f))
                Text(
                    "结果反映当前网络出口，切换 Wi-Fi 或代理后可重新检测。",
                    color = MutedInk.copy(alpha = .7f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun NetworkTopBar(onBack: () -> Unit, running: Boolean, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink) }
        Column(Modifier.weight(1f)) {
            Text("网络诊断", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (running) "正在检查网络…" else "检测完成", color = MutedInk, fontSize = 12.sp)
        }
        IconButton(onClick = onRefresh, enabled = !running) {
            if (running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SignalGreen)
            } else {
                Icon(Icons.Outlined.Refresh, "重新检测", tint = Ink)
            }
        }
    }
}

@Composable
private fun IpSection(state: NetworkDiagnosticState, onCopy: (String) -> Unit) {
    Spacer(Modifier.height(12.dp))
    when {
        state.ipInfo != null -> {
            val info = state.ipInfo
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info.ip,
                    color = Ink,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-.5).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onCopy(info.ip) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "复制 IP", tint = MutedInk, modifier = Modifier.size(18.dp))
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Public, null, tint = SignalGreen, modifier = Modifier.size(17.dp))
                Text(info.location, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Text(info.isp, color = MutedInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        }
        state.ipError != null -> {
            Text("公网 IP 查询失败", color = Vermilion, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(state.ipError, color = MutedInk, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
        }
        else -> {
            Box(Modifier.fillMaxWidth().height(74.dp), contentAlignment = Alignment.CenterStart) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = SignalGreen)
            }
        }
    }
}

@Composable
private fun SiteRow(result: SiteResult) {
    val statusColor = result.statusColor()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(
                statusColor.copy(alpha = if (result.reachable == null) .10f else .16f)
            ),
            contentAlignment = Alignment.Center,
        ) {
            when (result.reachable) {
                true -> Icon(Icons.Outlined.CheckCircle, null, tint = statusColor, modifier = Modifier.size(21.dp))
                false -> Icon(Icons.Outlined.WifiOff, null, tint = statusColor, modifier = Modifier.size(21.dp))
                null -> Icon(Icons.Outlined.Schedule, null, tint = MutedInk, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(result.target.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(result.target.host, color = MutedInk, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                when (result.reachable) { true -> "可连接"; false -> "不可连接"; null -> "检测中" },
                color = statusColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(result.latencyMs?.let { "$it ms" } ?: result.detail, color = statusColor, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(SoftLine))
}

private fun SiteResult.statusColor(): Color = when {
    reachable == false -> Vermilion
    reachable == null || latencyMs == null -> MutedInk
    latencyMs <= 200 -> SignalGreen
    latencyMs <= 500 -> SignalYellow
    latencyMs <= 1_000 -> SignalOrange
    else -> Vermilion
}
