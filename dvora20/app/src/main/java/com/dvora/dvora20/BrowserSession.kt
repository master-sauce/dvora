package com.dvora.dvora20

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The browser's app-session tab store: regular tabs live here — surviving
 * leaving / re-entering the browser screen for as long as the app is open.
 * The WebViews themselves belong to MainActivity, so [destroyAll] is called
 * from its onDestroy — the session never follows a dead Activity.
 * Incognito tabs are NOT session state: they die with the browser screen
 * (see BrowserScreen's onDispose), so nothing private is ever kept here.
 */
object TabCtl {
    /** every open tab — SnapshotStateList so the tab strip recomposes live */
    val tabs = mutableStateListOf<WebTab>()

    /** selected tab index into [tabs] — outlives the browser screen with them */
    val activeState = mutableIntStateOf(0)

    /** tears the whole session down — WebViews of a dead Activity must never render again */
    fun destroyAll() {
        tabs.toList().forEach { it.destroy() }
        tabs.clear()
        activeState.intValue = 0
    }

    /**
     * Drops every incognito tab (browser screen exit) — they are never saved.
     * Destroys them outright and re-coerces the active index afterwards.
     */
    fun dropIncognito() {
        val gone = tabs.toList().filter { it.incognito }
        if (gone.isEmpty()) return
        tabs.removeAll(gone)
        gone.forEach { it.destroy() }
        activeState.intValue = if (tabs.isEmpty()) 0 else activeState.intValue.coerceIn(0, tabs.lastIndex)
    }
}

/** one visited page — a row of the history dialog */
data class HistEntry(
    val url: String,
    val host: String,
    val title: String?,   // filled in when the page reports its title
    val at: Long          // visit time, epoch millis
)

/**
 * Browsing history of every committed page url — recorded by WebTab for
 * NORMAL tabs only; incognito tabs never call it, so nothing they visit is
 * ever written anywhere. Persisted to prefs (survives app restarts) and
 * wiped by the settings "clear browsing data" button.
 */
object HistoryStore {
    private const val PREFS_FILE = "dvora_prefs"   // shared with the other browser prefs
    private const val PREFS_KEY = "browser_history"
    private const val MAX = 500                     // hard cap — the newest N stay

    private var appCtx: Context? = null

    var entries by mutableStateOf<List<HistEntry>>(emptyList())
        private set

    /** one-time load — called from MainActivity.onCreate */
    fun load(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        val json = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(PREFS_KEY, null) ?: return
        entries = try {
            Gson().fromJson(json, object : TypeToken<List<HistEntry>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** newest entry lands on top; a reload / commit echo of the same url collapses */
    fun add(url: String) {
        if (!url.startsWith("http")) return
        if (entries.firstOrNull()?.url == url) return
        val host = Uri.parse(url).host ?: ""
        entries = (listOf(HistEntry(url, host, null, System.currentTimeMillis())) + entries).take(MAX)
        persist()
    }

    /** the page's title arrives after commit — fills the newest matching row */
    fun setTitle(url: String, title: String) {
        if (title.isBlank()) return
        val i = entries.indexOfFirst { it.url == url }
        if (i < 0) return
        entries = entries.toMutableList().apply { set(i, get(i).copy(title = title)) }
        persist()
    }

    fun clear() {
        entries = emptyList()
        persist()
    }

    private fun persist() {
        val ctx = appCtx ?: return
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, Gson().toJson(entries)).apply()
    }
}
