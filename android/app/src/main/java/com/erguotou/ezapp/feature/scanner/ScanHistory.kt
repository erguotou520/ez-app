package com.erguotou.ezapp.feature.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ScanRecord(val value: String, val format: String, val scannedAt: Long)

class ScanHistory(context: Context) {
    private val preferences = context.getSharedPreferences("scan_history", Context.MODE_PRIVATE)

    fun load(): List<ScanRecord> {
        val raw = preferences.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                ScanRecord(item.getString("value"), item.optString("format", "二维码"), item.getLong("scannedAt"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(record: ScanRecord) {
        val records = (listOf(record) + load().filterNot { it.value == record.value }).take(100)
        val array = JSONArray()
        records.forEach {
            array.put(JSONObject().put("value", it.value).put("format", it.format).put("scannedAt", it.scannedAt))
        }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    fun clear() = preferences.edit().remove(KEY).apply()

    private companion object { const val KEY = "records" }
}
