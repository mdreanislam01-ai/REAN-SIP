package com.reansip.dialer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists SIP accounts locally (SharedPreferences + JSON). No cloud, no tracking. */
class SipAccountStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<SipAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SipAccount(
                    id = o.optLong("id", System.currentTimeMillis() + i),
                    label = o.optString("label"),
                    username = o.optString("username"),
                    password = o.optString("password"),
                    domain = o.optString("domain"),
                    transport = o.optString("transport", "UDP"),
                    displayName = o.optString("displayName"),
                    registerEnabled = o.optBoolean("registerEnabled", true)
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun persist(accounts: List<SipAccount>) {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("label", a.label)
                put("username", a.username)
                put("password", a.password)
                put("domain", a.domain)
                put("transport", a.transport)
                put("displayName", a.displayName)
                put("registerEnabled", a.registerEnabled)
            })
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    /** Insert or update an account. The first saved account becomes active automatically. */
    fun upsert(account: SipAccount) {
        val l = list().toMutableList()
        val idx = l.indexOfFirst { it.id == account.id }
        if (idx >= 0) l[idx] = account else l.add(account)
        persist(l)
        if (activeAccount() == null) setActive(account.id)
    }

    fun delete(id: Long) {
        persist(list().filterNot { it.id == id })
        if (activeId() == id) setActive(list().firstOrNull()?.id ?: 0L)
    }

    fun activeId(): Long = prefs.getLong(KEY_ACTIVE, 0L)

    fun setActive(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE, id).apply()
    }

    fun activeAccount(): SipAccount? {
        val l = list()
        if (l.isEmpty()) return null
        val found = l.find { it.id == activeId() }
        return found ?: l.first().also { setActive(it.id) }
    }

    companion object {
        private const val PREFS = "rean_sip_accounts"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active"
    }
}
