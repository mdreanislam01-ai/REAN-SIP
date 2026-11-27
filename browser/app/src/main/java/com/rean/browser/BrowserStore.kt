package com.rean.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** On-device persistence for settings, history and bookmarks (SharedPreferences + JSON). */
class BrowserStore(context: Context) {

    data class Entry(val title: String, val url: String, val time: Long)

    private val prefs = context.getSharedPreferences("rean_browser_prefs", Context.MODE_PRIVATE)

    var homeUrl: String
        get() = prefs.getString(KEY_HOME, DEFAULT_HOME)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOME
        set(value) { prefs.edit().putString(KEY_HOME, value.trim()).apply() }

    var darkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK, false)
        set(value) { prefs.edit().putBoolean(KEY_DARK, value).apply() }

    var adBlock: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) { prefs.edit().putBoolean(KEY_ADBLOCK, value).apply() }

    // ---- history ----
    fun history(): List<Entry> = readList(KEY_HISTORY)

    fun addHistory(title: String, url: String) {
        val list = readList(KEY_HISTORY)
        if (list.firstOrNull()?.url == url) return
        writeList(KEY_HISTORY, (listOf(Entry(title, url, System.currentTimeMillis())) + list).take(MAX_HISTORY))
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    // ---- bookmarks ----
    fun bookmarks(): List<Entry> = readList(KEY_BOOKMARKS)

    fun isBookmarked(url: String): Boolean = bookmarks().any { it.url == url }

    fun addBookmark(title: String, url: String) {
        if (isBookmarked(url)) return
        writeList(KEY_BOOKMARKS, bookmarks() + Entry(title, url, System.currentTimeMillis()))
    }

    fun removeBookmark(url: String) {
        writeList(KEY_BOOKMARKS, bookmarks().filter { it.url != url })
    }

    // ---- JSON helpers ----
    private fun readList(key: String): List<Entry> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.optString("title"), o.optString("url"), o.optLong("time"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time))
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        const val DEFAULT_HOME = "https://duckduckgo.com"
        private const val MAX_HISTORY = 1000
        private const val KEY_HOME = "home_url"
        private const val KEY_DARK = "dark_mode"
        private const val KEY_ADBLOCK = "adblock"
        private const val KEY_HISTORY = "history"
        private const val KEY_BOOKMARKS = "bookmarks"
    }
}
