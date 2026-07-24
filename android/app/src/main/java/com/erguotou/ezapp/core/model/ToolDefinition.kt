package com.erguotou.ezapp.core.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val route: String,
    val icon: ImageVector,
    val accent: Color,
)
