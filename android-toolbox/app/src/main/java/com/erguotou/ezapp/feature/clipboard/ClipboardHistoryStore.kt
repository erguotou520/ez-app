package com.erguotou.ezapp.feature.clipboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardHistoryItem(
    val id: String,
    val text: String,
    val updatedAt: Long,
    val starred: Boolean = false,
)

class ClipboardHistoryStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    val items = ClipboardHistoryRuntime.items

    init {
        ClipboardHistoryRuntime.update(readItems())
    }

    @Synchronized
    fun add(id: String, text: String, updatedAt: Long) {
        val current = ClipboardHistoryRuntime.items.value
        val existing = current.firstOrNull { it.id == id }
        val item = ClipboardHistoryItem(
            id = id,
            text = text,
            updatedAt = updatedAt,
            starred = existing?.starred ?: false,
        )
        save(sortAndTrim(current.filterNot { it.id == id } + item))
    }

    @Synchronized
    fun toggleStar(id: String) {
        save(
            sortAndTrim(
                ClipboardHistoryRuntime.items.value.map { item ->
                    if (item.id == id) item.copy(starred = !item.starred) else item
                }
            )
        )
    }

    @Synchronized
    fun clearRegular() {
        save(ClipboardHistoryRuntime.items.value.filter(ClipboardHistoryItem::starred))
    }

    private fun sortAndTrim(items: List<ClipboardHistoryItem>): List<ClipboardHistoryItem> {
        val starred = items.filter(ClipboardHistoryItem::starred)
            .sortedByDescending(ClipboardHistoryItem::updatedAt)
        val regular = items.filterNot(ClipboardHistoryItem::starred)
            .sortedByDescending(ClipboardHistoryItem::updatedAt)
            .take(MAX_REGULAR_ITEMS)
        return starred + regular
    }

    private fun save(items: List<ClipboardHistoryItem>) {
        ClipboardHistoryRuntime.update(items)
        val json = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("text", item.text)
                        .put("updatedAt", item.updatedAt)
                        .put("starred", item.starred)
                )
            }
        }
        preferences.edit().putString(KEY_ITEMS, json.toString()).apply()
    }

    private fun readItems(): List<ClipboardHistoryItem> = runCatching {
        val array = JSONArray(preferences.getString(KEY_ITEMS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    ClipboardHistoryItem(
                        id = item.getString("id"),
                        text = item.getString("text"),
                        updatedAt = item.getLong("updatedAt"),
                        starred = item.optBoolean("starred"),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val FILE_NAME = "clipboard_history"
        private const val KEY_ITEMS = "items"
        private const val MAX_REGULAR_ITEMS = 100
    }
}

private object ClipboardHistoryRuntime {
    private val _items = MutableStateFlow<List<ClipboardHistoryItem>>(emptyList())
    val items = _items.asStateFlow()

    fun update(items: List<ClipboardHistoryItem>) {
        _items.value = items
    }
}
