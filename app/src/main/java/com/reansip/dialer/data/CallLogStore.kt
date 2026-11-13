package com.reansip.dialer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentCall(
    val number: String,
    val displayName: String,
    val direction: String, // "in" | "out"
    val missed: Boolean,
    val durationSec: Int,
    val timestamp: Long
)

/** Local call history — stored on-device only, capped at [MAX_ENTRIES]. */
class CallLogStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(entry: RecentCall) {
        val l = list().toMutableList()
        l.add(0, entry)
        while (l.size > MAX_ENTRIES) l.removeAt(l.size - 1)
        persist(l)
    }

    fun list(): List<RecentCall> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentCall(
                    number = o.optString("number"),
                    displayName = o.optString("displayName"),
                    direction = o.optString("direction", "out"),
                    missed = o.optBoolean("missed", false),
                    durationSec = o.optInt("durationSec", 0),
                    timestamp = o.optLong("timestamp", 0L)
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().putString(KEY, "[]").apply()
    }

    private fun persist(l: List<RecentCall>) {
        val arr = JSONArray()
        l.forEach { e ->
            arr.put(JSONObject().apply {
                put("number", e.number)
                put("displayName", e.displayName)
                put("direction", e.direction)
                put("missed", e.missed)
                put("durationSec", e.durationSec)
                put("timestamp", e.timestamp)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "rean_call_log"
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 200
    }
}
