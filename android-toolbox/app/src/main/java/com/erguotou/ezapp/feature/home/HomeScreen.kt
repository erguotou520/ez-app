package com.erguotou.ezapp.feature.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erguotou.ezapp.core.model.ToolRegistry
import com.erguotou.ezapp.ui.components.AppScreen
import com.erguotou.ezapp.ui.theme.Ink
import com.erguotou.ezapp.ui.theme.MutedInk
import com.erguotou.ezapp.ui.theme.Paper
import com.erguotou.ezapp.ui.theme.SoftLine

@Composable
fun HomeScreen(onOpenTool: (String) -> Unit) {
    AppScreen(background = Paper) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(52.dp))
            Text("拾光", color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
            Text("把日常小事，做得轻一点。", color = MutedInk, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(52.dp))
            Text("我的工具", style = MaterialTheme.typography.labelLarge, color = MutedInk)
            Spacer(Modifier.height(14.dp))

            ToolRegistry.tools.forEach { tool ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTool(tool.route) }
                        .padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(58.dp).clip(CircleShape).background(tool.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tool.icon, contentDescription = null, tint = Color(0xFFFFFBF4), modifier = Modifier.size(27.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 17.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tool.name, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(tool.description, color = MutedInk, fontSize = 13.sp)
                    }
                    Icon(Icons.Outlined.ArrowForward, contentDescription = "打开${tool.name}", tint = Ink)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SoftLine))
            }

            Spacer(Modifier.weight(1f))
            Text("更多工具，正在路上", color = MutedInk.copy(alpha = .65f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 20.dp))
        }
    }
}
