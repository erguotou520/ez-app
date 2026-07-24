package com.erguotou.ezapp.feature.clipboard

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast

class ClipboardCaptureActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ClipboardSyncPreferences(this).enabled) {
            Toast.makeText(this, "请先在拾光中开启剪切板共享", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled || isFinishing) return
        handled = true

        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "剪切板里没有文本", Toast.LENGTH_SHORT).show()
        } else if (ClipboardSyncRuntime.shouldIgnoreClipboard(text)) {
            Toast.makeText(this, "这是刚从电脑收到的内容", Toast.LENGTH_SHORT).show()
        } else {
            ClipboardSyncService.send(this, text)
            Toast.makeText(this, "正在同步剪切板", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
