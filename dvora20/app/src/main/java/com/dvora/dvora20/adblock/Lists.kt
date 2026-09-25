package com.dvora.dvora20.adblock

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The four EasyList-family filter lists shipped with the browser.
 *
 * Every list is its official source (easylist.to / the EasyListHebrew project
 * on GitHub, https://github.com/easylist/EasyListHebrew). A bundled copy in
 * `assets/filters/` acts as an offline fallback for the very first run, and
 * every downloaded copy is cached under `filesDir/filter_lists/` so a later
 * failed or corrupted (checksum mismatch) download never breaks blocking —
 * the last-known-good copy keeps being served.
 */
enum class ListInfo(
    val id: String,             // prefs + cache-file key
    val url: String,            // official HTTPS source
    val asset: String,          // bundled fallback file name under assets/filters/
    val displayName: String
) {
    EASYLIST(
        "easylist",
        "https://easylist.to/easylist/easylist.txt",
        "easylist.txt",
        "EasyList (ads)"
    ),
    PRIVACY(
        "privacy",
        "https://easylist.to/easyprivacy/easyprivacy.txt",
        "easyprivacy.txt",
        "EasyPrivacy (trackers)"
    ),
    HEBREW(
        "hebrew",
        "https://raw.githubusercontent.com/easylist/EasyListHebrew/master/EasyListHebrew.txt",
        "easylisthebrew.txt",
        "EasyList Hebrew"
    ),
    COOKIE(
        "cookie",
        "https://easylist.to/list/easylistcookie.txt",
        "easylistcookie.txt",
        "EasyList Cookie (banners)"
    );

    companion object {
        val all = values()
    }
}

/**
 * Downloads, caches, compiles and exposes the filter lists.
 *
 * All file / HTTP / parsing work runs on IO; the merged engine is rebuilt
 * off the UI thread and swapped in through a @Volatile reference, so a
 * running page always sees one consistent, previously-good engine until the
 * new one is ready.
 */
class ListRepo(private val appCtx: Context) {

    private val prefs = appCtx.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
    private val dir = File(appCtx.filesDir, "filter_lists")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(25))
        .readTimeout(Duration.ofSeconds(90))
        .followRedirects(true)
        .build()

    @Volatile
    var engine: BlockerEngine = BlockerEngine()
        private set

    /** latest parsed rules of every list, whether enabled or not. */
    private val net = ConcurrentHashMap<ListInfo, ArrayList<FilterRule>>()
    private val cos = ConcurrentHashMap<ListInfo, ArrayList<CosmeticRule>>()

    /** short human status per list for the settings UI. */
    @Volatile
    var statuses: Map<ListInfo, String> = emptyMap()
        private set

    @Volatile
    var loaded: Boolean = false
        private set

    private val staleAfter = Duration.ofDays(7)
    private val initFlag = AtomicBoolean()

    // ── simple persisted settings (shared prefs file with the whole app) ─────

    fun isEnabled(l: ListInfo): Boolean = prefs.getBoolean("fl_${l.id}", true)

    fun setEnabled(l: ListInfo, enabled: Boolean) {
        prefs.edit().putBoolean("fl_${l.id}", enabled).apply()
        if (loaded) scope.launch { recompile() }
    }

    fun isMaster(): Boolean = prefs.getBoolean("adblock_master", true)

    fun setMaster(on: Boolean) {
        prefs.edit().putBoolean("adblock_master", on).apply()
    }

    fun isDownloaded(l: ListInfo): Boolean = File(dir, "${l.id}.txt").exists()

    fun lastUpdated(l: ListInfo): Long = prefs.getLong("fl_${l.id}_ts", 0L)

    fun persistedLifetime(): Long = prefs.getLong("lifetime_blocks", 0L)

    /** writes the running lifetime counter back to prefs. */
    fun flushLifetime() {
        val v = engine.lifetime
        if (v > 0) prefs.edit().putLong("lifetime_blocks", v).apply()
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    /**
     * One-shot async start (call once, e.g. from the app's main coroutine):
     * reads every list from disk cache or the bundled asset, publishes the
     * compiled engine, then kicks off background downloads for lists that are
     * stale (never fetched, or older than 7 days).
     */
    fun init(scope: CoroutineScope) {
        if (!initFlag.compareAndSet(false, true)) return
        scope.launch {
            withContext(Dispatchers.IO) {
                for (l in ListInfo.all) readStored(l)
                loaded = true
                recompile()
            }
            maybeRefresh(scope)
        }
    }

    /** background auto-refresh of every stale list. */
    fun maybeRefresh(scope: CoroutineScope) {
        for (l in ListInfo.all) if (stale(l)) refresh(scope, l)
    }

    /** manual (settings button) or automatic single-list refresh — background. */
    fun refresh(scope: CoroutineScope, l: ListInfo) {
        setStatus(l, "updating…")
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    doRefresh(l)
                } catch (e: Exception) {
                    setStatus(l, "update failed (${e.message?.take(60) ?: "?"})")
                }
            }
        }
    }

    private fun doRefresh(l: ListInfo) {
        val text = try {
            val req = Request.Builder().url(l.url)
                .header("Accept", "text/plain;q=1.0,*/*;q=0.8")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Dvora/1.0")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IOException("empty response")
            }
        } catch (e: Exception) {
            setStatus(l, "update failed (${e.message?.take(60) ?: "network"})")
            return                                    // cached / bundled copy stays active
        }
        val problem = validate(text)
        if (problem != null) {
            setStatus(l, "rejected: $problem")        // corrupted download — cache survives
            return
        }
        val p = parseAll(text, l)
        if (!dir.exists()) dir.mkdirs()
        val dst = File(dir, "${l.id}.txt")
        val tmp = File(dir, "${l.id}.txt.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(dst) && !dst.delete() && !tmp.renameTo(dst)) tmp.delete()
        prefs.edit().putLong("fl_${l.id}_ts", System.currentTimeMillis()).apply()
        net[l] = p.first
        cos[l] = p.second
        setStatus(l, "updated · ${p.first.size} rules")
        if (loaded) recompile()
    }

    private fun stale(l: ListInfo): Boolean {
        if (!File(dir, "${l.id}.txt").exists()) return true
        if (lastUpdated(l) <= 0L) return true
        return Duration.between(Instant.ofEpochMilli(lastUpdated(l)), Instant.now()) > staleAfter
    }

    private fun setStatus(l: ListInfo, msg: String) {
        statuses = statuses.toMutableMap().apply { put(l, msg) }
    }

    /** lines like `! Checksum: …` — excluded from the digest body. */
    private val checksumLine = Regex("""^\\s*!?\\s*checksum\\s*:""", RegexOption.IGNORE_CASE)

    /**
     * Integrity gate: when the list ships a `! Checksum:` header (EasyList
     * Hebrew does; easylist.to's lists don't), verify it against our own
     * digest of the same text, plus require a sensible rule count. A failed
     * check ⇒ the download is rejected and the cached copy keeps being served.
     * Returns a short problem note, or null = accepted.
     */
    fun validate(text: String): String? {
        val lines = text.split(Regex("\\r\\n|\\n|\\r"))
        val rules = lines.count { val t = it.trim(); t.isNotEmpty() && !t.startsWith("!") && !t.startsWith("[") }
        if (rules < 40) return "too few rules ($rules)"
        val header = lines.firstNotNullOfOrNull {
            val t = it.trim()
            if (t.startsWith("! Checksum:")) t.substringAfter("! Checksum:").trim() else null
        }
        if (header == null) return null          // no header on this list — size check is all we have
        val computed = checksum(text)
        if (!header.equals(computed, ignoreCase = true)) return "checksum mismatch ($computed)"
        return null
    }

    /**
     * Base64(MD5) digest (padding stripped) over every line except the
     * checksum line itself — byte-for-byte what EasyList Hebrew's own
     * `.github/scripts/validateChecksum.py` (reference Adblock Plus tooling)
     * computes, so it matches their published `! Checksum:` headers.
     */
    fun checksum(text: String): String {
        val lines = text.split(Regex("\\r\\n|\\n|\\r")).filter { !checksumLine.containsMatchIn(it) }
        val digest = MessageDigest.getInstance("MD5").digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest).replace("=", "")
    }

    private fun parseAll(text: String, l: ListInfo): Pair<ArrayList<FilterRule>, ArrayList<CosmeticRule>> {
        val nr = ArrayList<FilterRule>()
        val cr = ArrayList<CosmeticRule>()
        val list = l.displayName
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("!") || t.startsWith("[") || t.startsWith("=")) continue
            if (t.startsWith("@!") || t.startsWith("@@") || t.startsWith("##")) {
                FilterParser.parseCosmetic(t, list)?.let { cr += it }
            } else {
                FilterParser.parse(t, list)?.let { nr += it }
            }
        }
        return nr to cr
    }

    private fun readStored(l: ListInfo) {
        val f = File(dir, "${l.id}.txt")
        var text = if (f.exists()) try {
            f.readText()
        } catch (_: Exception) {
            null
        } else null
        if (text == null) {
            try {
                text = appCtx.assets.open("filters/${l.asset}").bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            }
        }
        if (text == null) {
            setStatus(l, "empty – waiting for download")
            return
        }
        val p = parseAll(text, l)
        net[l] = p.first
        cos[l] = p.second
        setStatus(l, if (f.exists()) "ready · ${p.first.size} rules" else "bundled fallback · ${p.first.size} rules")
    }

    /** recompiles every enabled list's rules into one fresh engine and swaps it in. */
    fun recompile() {
        if (!loaded) return
        val all = ArrayList<FilterRule>()
        val allCos = ArrayList<CosmeticRule>()
        for (l in ListInfo.all) {
            if (!isEnabled(l)) continue
            val n = net[l]
            val c = cos[l] ?: continue
            if (n != null) all += n
            allCos += c
        }
        engine = BlockerEngine(all, allCos, persistedLifetime())
    }
}

/**
 * One shared repo instance per process — used both by the browser screen
 * and by the settings UI so state (statuses / lists) stays consistent.
 */
object Repo {
    @Volatile
    var repo: ListRepo? = null
        private set

    /** idempotent — kicks the async list load on first call. */
    fun get(ctx: Context): ListRepo {
        var r = repo
        if (r == null) {
            synchronized(this) {
                r = repo ?: ListRepo(ctx.applicationContext).also {
                    repo = it
                    it.init(CoroutineScope(Dispatchers.Default + SupervisorJob()))
                }
            }
        }
        return r!!
    }
}
