package com.dvora.dvora20.adblock

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Per-request exceptions — single urls the user allowed from the resources panel
 * ("allow this request"). Exact url match, stored in the app-wide `dvora_prefs`
 * next to the per-site whitelist, so the interceptor can consult a cached list
 * on every request without touching disk.
 *
 * The resources panel lists them under "excluded from ad blocker" and can drop
 * them again from there.
 */
object Exceptions {

    private const val PREFS = "dvora_prefs"
    private const val KEY = "browser_exceptions"
    private const val MAX = 500

    private val gson = Gson()
    private val lock = Any()
    private val listType = object : TypeToken<List<String>>() {}.type

    @Volatile
    private var prefs: SharedPreferences? = null

    /** in-memory snapshot — read on EVERY intercepted request, so never touch disk there */
    @Volatile
    private var cached: List<String>? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(lock) {
            prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .also { prefs = it }
        }

    fun all(context: Context): List<String> = cached ?: synchronized(lock) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): List<String> = try {
        val raw = prefs(context).getString(KEY, "") ?: ""
        if (raw.isEmpty()) emptyList() else gson.fromJson(raw, listType) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun has(context: Context, url: String): Boolean = all(context).contains(url)

    fun add(context: Context, url: String) {
        val set = LinkedHashSet(all(context))
        if (set.add(url)) save(context, set.toList().takeLast(MAX))
    }

    fun remove(context: Context, url: String) {
        val set = LinkedHashSet(all(context))
        if (set.remove(url)) save(context, set.toList())
    }

    private fun save(context: Context, urls: List<String>) {
        prefs(context).edit().putString(KEY, gson.toJson(urls)).apply()
        cached = urls       // the interceptor reads this live — keep it current
    }
}
