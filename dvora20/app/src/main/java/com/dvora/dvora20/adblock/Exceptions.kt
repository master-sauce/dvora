package com.dvora.dvora20.adblock

import android.content.Context

/**
 * SESSION-ONLY per-request exceptions: urls the user allowed from the requests
 * panel or the "this page is blocked" prompt. Nothing is written to disk — the
 * list lives as long as the app process and is dropped the moment Dvora closes.
 *
 * The interceptor reads the cached list on every request, so no disk access ever
 * happens on the network path.
 */
object Exceptions {

    private val lock = Any()

    /** in-memory snapshot — read on EVERY intercepted request */
    @Volatile
    private var cached: List<String> = emptyList()

    fun all(@Suppress("UNUSED_PARAMETER") context: Context): List<String> = cached

    fun has(@Suppress("UNUSED_PARAMETER") context: Context, url: String): Boolean =
        cached.contains(url)

    fun add(@Suppress("UNUSED_PARAMETER") context: Context, url: String) {
        synchronized(lock) {
            if (!cached.contains(url)) cached = cached + url
        }
    }

    fun addAll(@Suppress("UNUSED_PARAMETER") context: Context, urls: Collection<String>) {
        if (urls.isEmpty()) return
        synchronized(lock) { cached = (cached + urls).distinct() }
    }

    fun remove(@Suppress("UNUSED_PARAMETER") context: Context, url: String) {
        synchronized(lock) { cached = cached - url }
    }

    /** app closed → nothing of this session survives */
    fun clear() {
        synchronized(lock) { cached = emptyList() }
    }

    /** older builds stored exclusions in prefs — wipe that key so none come back */
    fun clearLegacyStore(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
                .edit().remove("browser_exceptions").apply()
        }
    }
}
