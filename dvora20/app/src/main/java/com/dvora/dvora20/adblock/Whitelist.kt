package com.dvora.dvora20.adblock

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Per-site blocking whitelist — domains toggled "allowed" in the browser's
 * shield sheet. Kept in the app-wide `dvora_prefs` (same prefs file the rest
 * of the app settings already use), so no extra storage dependency is needed.
 *
 * Stored as a compact JSON list of registrable hosts; every host (and all of
 * its sub-domains) is fully allowed by the filter engine.
 */
object Whitelist {

    private const val PREFS = "dvora_prefs"
    private const val KEY = "browser_whitelist"

    private val gson = Gson()
    private val lock = Any()
    private val listType = object : TypeToken<List<String>>() {}.type

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(lock) {
            prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .also { prefs = it }
        }

    fun hosts(context: Context): List<String> = try {
        val raw = prefs(context).getString(KEY, "") ?: ""
        if (raw.isEmpty()) emptyList()
        else gson.fromJson(raw, listType) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun isAllowed(host: String, hosts: List<String>): Boolean =
        hosts.any { host == it || host.endsWith(".$it") }

    fun add(context: Context, host: String) {
        val set = LinkedHashSet<String>(hosts(context))
        if (set.add(host)) save(context, set)
    }

    fun remove(context: Context, host: String) {
        val set = LinkedHashSet<String>(hosts(context))
        if (set.removeAll { host == it || host.endsWith(".$it") }) save(context, set)
    }

    private fun save(context: Context, hosts: Collection<String>) {
        prefs(context).edit().putString(KEY, gson.toJson(ArrayDeque(hosts))).apply()
    }
}
