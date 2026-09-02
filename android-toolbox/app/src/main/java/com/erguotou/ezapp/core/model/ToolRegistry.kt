package com.erguotou.ezapp.core.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.outlined.ContentPasteGo
import com.erguotou.ezapp.ui.theme.BridgeBlue
import com.erguotou.ezapp.ui.theme.SignalGreen
import com.erguotou.ezapp.ui.theme.Vermilion

object ToolRegistry {
    val tools = listOf(
        ToolDefinition(
            id = "scanner",
            name = "扫一扫",
            description = "二维码 · 条形码 · 相册识别",
            route = "scanner",
            icon = Icons.Outlined.QrCodeScanner,
            accent = Vermilion,
        ),
        ToolDefinition(
            id = "network",
            name = "网络诊断",
            description = "连通性 · 公网 IP · 归属地",
            route = "network",
            icon = Icons.Outlined.WifiTethering,
            accent = SignalGreen,
        ),
        ToolDefinition(
            id = "clipboard",
            name = "剪切板共享",
            description = "手机 · 电脑 · 局域网加密",
            route = "clipboard",
            icon = Icons.Outlined.ContentPasteGo,
            accent = BridgeBlue,
        ),
    )
}
