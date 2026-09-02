package com.erguotou.ezapp.feature.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScanHistory(application)
    private val _history = MutableStateFlow(store.load())
    val history = _history.asStateFlow()

    fun save(value: String, format: String) {
        store.add(ScanRecord(value, format, System.currentTimeMillis()))
        _history.value = store.load()
    }

    fun clearHistory() {
        store.clear()
        _history.value = emptyList()
    }

    fun scanImage(uri: Uri, onResult: (String?, String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val image = InputImage.fromFilePath(getApplication(), uri)
                BarcodeScanning.getClient().use { scanner -> scanner.process(image).await().firstOrNull() }
            }.getOrNull()
            onResult(result?.rawValue, result?.formatLabel())
        }
    }
}
