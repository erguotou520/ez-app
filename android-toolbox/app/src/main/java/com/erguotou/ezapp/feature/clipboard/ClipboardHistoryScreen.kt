package com.erguotou.ezapp.feature.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erguotou.ezapp.ui.components.AppScreen
import com.erguotou.ezapp.ui.theme.Ink
import com.erguotou.ezapp.ui.theme.MutedInk
import com.erguotou.ezapp.ui.theme.Paper
import com.erguotou.ezapp.ui.theme.Vermilion

@Composable
fun ClipboardHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val historyStore = remember { ClipboardHistoryStore(context) }
    val history by historyStore.items.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    AppScreen(background = Paper) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = 8.dp, end = 24.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink)
                }
                Column(Modifier.weight(1f)) {
                    Text("共享历史", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "收藏 ${history.count { it.starred }} · 普通 ${history.count { !it.starred }}/100",
                        color = MutedInk,
                        fontSize = 12.sp,
                    )
                }
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = history.any { !it.starred },
                ) {
                    Text("清除历史", color = Vermilion)
                }
            }

            if (history.isEmpty()) {
                Text(
                    "还没有共享记录\n同步过的文本会按时间出现在这里。",
                    color = MutedInk,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 36.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    items(history, key = ClipboardHistoryItem::id) { item ->
                        ClipboardHistoryRow(
                            item = item,
                            onCopy = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("共享历史", item.text))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            onToggleStar = { historyStore.toggleStar(item.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除共享历史？") },
            text = { Text("将清除所有未收藏的记录，已收藏内容会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        historyStore.clearRegular()
                        confirmClear = false
                        Toast.makeText(context, "未收藏记录已清除", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("确认清除", color = Vermilion)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}
