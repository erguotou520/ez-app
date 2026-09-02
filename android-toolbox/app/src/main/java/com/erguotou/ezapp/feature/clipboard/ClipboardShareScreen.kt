package com.erguotou.ezapp.feature.clipboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.erguotou.ezapp.ui.components.AppScreen
import com.erguotou.ezapp.ui.theme.BridgeBlue
import com.erguotou.ezapp.ui.theme.Ink
import com.erguotou.ezapp.ui.theme.MutedInk
import com.erguotou.ezapp.ui.theme.Paper
import com.erguotou.ezapp.ui.theme.SignalGreen
import com.erguotou.ezapp.ui.theme.SoftLine
import com.erguotou.ezapp.ui.theme.Vermilion
import java.text.DateFormat
import java.util.Date

@Composable
fun ClipboardShareScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMqttSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember { ClipboardSyncPreferences(context) }
    val runtime by ClipboardSyncRuntime.state.collectAsState()

    var enabled by remember { mutableStateOf(preferences.enabled) }
    var pendingStart by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    fun startSharing() {
        preferences.enabled = true
        enabled = true
        ClipboardSyncService.start(context)
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (pendingStart) {
            pendingStart = false
            startSharing()
        }
    }

    fun requestStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSharing()
        }
    }

    fun stopSharing() {
        preferences.enabled = false
        enabled = false
        ClipboardSyncService.stop(context)
    }

    fun sendClipboard(showEmptyMessage: Boolean) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        when {
            text.isEmpty() && showEmptyMessage ->
                Toast.makeText(context, "剪切板里没有文本", Toast.LENGTH_SHORT).show()
            text.isNotEmpty() && !ClipboardSyncRuntime.shouldIgnoreClipboard(text) ->
                ClipboardSyncService.send(context, text)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = preferences.enabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    AppScreen(background = Paper) {
        Column(Modifier.fillMaxSize()) {
            ClipboardTopBar(onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = 16.dp,
                    bottom = 28.dp,
                ),
            ) {
                item {
                    SharingSwitch(enabled = enabled, onChange = { turnOn ->
                        if (turnOn) requestStart() else stopSharing()
                    })
                    Spacer(Modifier.height(30.dp))
                    ConnectionStatus(runtime, enabled)
                    Spacer(Modifier.height(34.dp))
                    Button(
                        onClick = { sendClipboard(showEmptyMessage = true) },
                        enabled = enabled && runtime.connection == ClipboardConnectionState.CONNECTED,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BridgeBlue,
                            contentColor = Color(0xFF101C29),
                        ),
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Outlined.ContentPasteGo, null, modifier = Modifier.size(20.dp))
                        Text("同步当前剪切板", modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(28.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(SoftLine))
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenHistory)
                            .padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共享历史",
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.ChevronRight, "打开共享历史", tint = MutedInk)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(SoftLine))
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(onClick = onOpenMqttSettings)
                            .padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "远程同步设置",
                                color = Ink,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (preferences.hasMqttConfig()) {
                                    "已配置 · ${preferences.mqttUsername}"
                                } else {
                                    "配置 MQTT 账号，支持跨网络同步"
                                },
                                color = MutedInk,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, "打开远程同步设置", tint = MutedInk)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(SoftLine))
                    if (preferences.trustedPeerKey != null) {
                        OutlinedButton(
                            onClick = { confirmForget = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                        ) {
                            Text("忘记已配对电脑", color = Vermilion)
                        }
                    }
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("忘记这台电脑？") },
            text = { Text("将断开当前连接。下次开启共享时，会把发现的电脑作为新设备重新配对。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        stopSharing()
                        preferences.trustedPeerKey = null
                        Toast.makeText(context, "已忘记配对电脑", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("确认忘记", color = Vermilion)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun ClipboardHistoryRow(
    item: ClipboardHistoryItem,
    onCopy: () -> Unit,
    onToggleStar: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.text,
                color = Ink,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(item.updatedAt)),
                color = MutedInk,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        IconButton(onClick = onToggleStar) {
            Icon(
                if (item.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                if (item.starred) "取消收藏" else "收藏",
                tint = if (item.starred) BridgeBlue else MutedInk,
            )
        }
    }
}

@Composable
private fun ClipboardTopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink)
        }
        Column(Modifier.weight(1f)) {
            Text("剪切板共享", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("手机与电脑", color = MutedInk, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SharingSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("共享功能", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (enabled) "后台连接已开启" else "关闭时不运行任何后台任务",
                color = MutedInk,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun ConnectionStatus(runtime: ClipboardRuntimeState, enabled: Boolean) {
    val state = if (enabled) runtime.connection else ClipboardConnectionState.OFF
    val color = when (state) {
        ClipboardConnectionState.CONNECTED -> SignalGreen
        ClipboardConnectionState.CONNECTING -> BridgeBlue
        ClipboardConnectionState.ERROR -> Vermilion
        ClipboardConnectionState.OFF -> MutedInk
    }
    val icon = when (state) {
        ClipboardConnectionState.CONNECTED -> Icons.Outlined.CheckCircle
        ClipboardConnectionState.CONNECTING -> Icons.Outlined.Link
        ClipboardConnectionState.ERROR -> Icons.Outlined.ErrorOutline
        ClipboardConnectionState.OFF -> Icons.Outlined.LinkOff
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SoftLine.copy(alpha = .45f)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = .16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                when (state) {
                    ClipboardConnectionState.CONNECTED -> runtime.peerName ?: "电脑已连接"
                    ClipboardConnectionState.CONNECTING -> "正在连接"
                    ClipboardConnectionState.ERROR -> "连接异常"
                    ClipboardConnectionState.OFF -> "共享已关闭"
                },
                color = Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (enabled) runtime.detail else "打开开关后会自动寻找电脑，无需填写配置",
                color = MutedInk,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Icon(Icons.Outlined.Computer, null, tint = color.copy(alpha = .8f), modifier = Modifier.size(22.dp))
    }
}
