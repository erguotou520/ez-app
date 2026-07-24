package com.erguotou.ezapp.feature.clipboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erguotou.ezapp.ui.components.AppScreen
import com.erguotou.ezapp.ui.theme.BridgeBlue
import com.erguotou.ezapp.ui.theme.Ink
import com.erguotou.ezapp.ui.theme.MutedInk
import com.erguotou.ezapp.ui.theme.Paper
import com.erguotou.ezapp.ui.theme.SoftLine

@Composable
fun ClipboardMqttSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ClipboardSyncPreferences(context) }
    var username by remember { mutableStateOf(preferences.mqttUsername) }
    var password by remember { mutableStateOf(preferences.mqttPassword) }

    AppScreen(background = Paper) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = 8.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink)
                }
                Column(Modifier.weight(1f)) {
                    Text("远程同步设置", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("只需填写 MQTT 账号", color = MutedInk, fontSize = 12.sp)
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp),
            ) {
                Icon(Icons.Outlined.CloudSync, null, tint = BridgeBlue)
                Text(
                    "跨网络同步",
                    color = Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "手机和电脑填写相同的用户名、密码即可。传输内容会在发送前加密。",
                    color = MutedInk,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text("服务器", color = MutedInk, fontSize = 12.sp)
                Text(
                    "${ClipboardSyncPreferences.MQTT_HOST}:${ClipboardSyncPreferences.MQTT_PORT}",
                    color = Ink,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp)
                        .background(SoftLine.copy(alpha = .45f)).padding(14.dp),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("MQTT 用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("MQTT 密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Button(
                    onClick = {
                        preferences.mqttUsername = username
                        preferences.mqttPassword = password
                        if (preferences.enabled) ClipboardSyncService.start(context)
                        Toast.makeText(context, "远程同步设置已保存", Toast.LENGTH_SHORT).show()
                        onBack()
                    },
                    enabled = username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 12.dp),
                ) {
                    Text("保存设置", color = Color(0xFF101C29), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
