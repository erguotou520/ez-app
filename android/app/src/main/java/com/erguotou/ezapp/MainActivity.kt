package com.erguotou.ezapp

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.erguotou.ezapp.feature.home.HomeScreen
import com.erguotou.ezapp.feature.clipboard.ClipboardShareScreen
import com.erguotou.ezapp.feature.clipboard.ClipboardHistoryScreen
import com.erguotou.ezapp.feature.clipboard.ClipboardMqttSettingsScreen
import com.erguotou.ezapp.feature.clipboard.ClipboardSyncPreferences
import com.erguotou.ezapp.feature.clipboard.ClipboardSyncRuntime
import com.erguotou.ezapp.feature.clipboard.ClipboardSyncService
import com.erguotou.ezapp.feature.network.NetworkDiagnosticScreen
import com.erguotou.ezapp.feature.scanner.ScannerScreen
import com.erguotou.ezapp.ui.theme.EzTheme

class MainActivity : ComponentActivity() {
    private var launchScanner by mutableStateOf(false)
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val preferences = ClipboardSyncPreferences(this)
        if (!preferences.enabled || !hasWindowFocus()) return@OnPrimaryClipChangedListener
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        if (text.isNotEmpty() && !ClipboardSyncRuntime.shouldIgnoreClipboard(text)) {
            ClipboardSyncService.send(this, text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchScanner = intent.action == ACTION_SCAN
        setContent {
            EzTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = if (launchScanner) "scanner" else "home",
                ) {
                    composable("home") {
                        HomeScreen(onOpenTool = navController::navigate)
                    }
                    composable("scanner") {
                        ScannerScreen(onBack = navController::popBackStack)
                    }
                    composable("network") {
                        NetworkDiagnosticScreen(onBack = navController::popBackStack)
                    }
                    composable("clipboard") {
                        ClipboardShareScreen(
                            onBack = navController::popBackStack,
                            onOpenHistory = { navController.navigate("clipboard/history") },
                            onOpenMqttSettings = {
                                navController.navigate("clipboard/mqtt-settings")
                            },
                        )
                    }
                    composable("clipboard/history") {
                        ClipboardHistoryScreen(onBack = navController::popBackStack)
                    }
                    composable("clipboard/mqtt-settings") {
                        ClipboardMqttSettingsScreen(onBack = navController::popBackStack)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SCAN) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        if (ClipboardSyncPreferences(this).enabled) {
            ClipboardSyncService.start(this)
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onStop() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .removePrimaryClipChangedListener(clipboardListener)
        super.onStop()
    }

    companion object {
        const val ACTION_SCAN = "com.erguotou.ezapp.action.SCAN"
    }
}
