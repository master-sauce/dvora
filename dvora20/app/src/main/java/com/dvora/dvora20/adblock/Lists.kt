package com.dvora.dvora20.adblock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Every filter list shipped with the browser.
 *
 * Each list is its official source — bundled into `assets/filters/` so the
 * very first run blocks out of the box, offline included. (`asset` empty =
 * no bundled copy; such a list simply arrives on first start.) Every
 * downloaded copy is cached under `filesDir/filter_lists/` so a later failed
 * or corrupted (checksum mismatch) download never breaks blocking — the
 * last-known-good copy keeps being served.
 *
 * [enforceChecksum] is false only for lists whose published `! Checksum:`
 * header does not follow the standard Adblock Plus tooling (ABP's own
 * anti-adblock list) — those would otherwise be rejected on every update.
 *
 * [defaultEnabled] keeps languages the user is unlikely to need (and the
 * 233k-rule oisd list) OFF until switched on in settings — 600k rules at
 * once is an OutOfMemory risk on a phone. Disabled lists are neither parsed
 * nor refreshed: they cost nothing until enabled.
 */
enum class ListInfo(
    val id: String,             // prefs + cache-file key
    val url: String,            // official HTTPS source
    val asset: String,          // bundled fallback file name under assets/filters/ ("" = none)
    val displayName: String,
    val enforceChecksum: Boolean = true,
    val defaultEnabled: Boolean = true
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
    ),

    // ── uBlock Origin's own lists ────────────────────────────────────────────
    UBLOCK(
        "ublock", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "ublock.txt", "uBlock filters (ads)"
    ),
    UBLOCK_UNBREAK(
        "ublock_unbreak", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
        "ublock_unbreak.txt", "uBlock — Unbreak (exceptions)"
    ),
    UBLOCK_ABUSE(
        "ublock_abuse", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/resource-abuse.txt",
        "ublock_abuse.txt", "uBlock — Resource abuse"
    ),
    UBLOCK_ANNOY(
        "ublock_annoy", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-others.txt",
        "ublock_annoy.txt", "uBlock — Other annoyances"
    ),

    // ── AdGuard lists ────────────────────────────────────────────────────────
    ADGUARD_BASE(
        "adguard_base", "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt",
        "adguard_base.txt", "AdGuard Base (no EasyList)"
    ),
    ADGUARD_MOBILE(
        "adguard_mobile", "https://filters.adtidy.org/extension/ublock/filters/11.txt",
        "adguard_mobile.txt", "AdGuard Mobile Ads"
    ),
    ADGUARD_ANNOY(
        "adguard_annoy", "https://filters.adtidy.org/extension/ublock/filters/14.txt",
        "adguard_annoy.txt", "AdGuard Annoyances"
    ),
    ADGUARD_SOCIAL(
        "adguard_social", "https://filters.adtidy.org/extension/ublock/filters/9.txt",
        "adguard_social.txt", "AdGuard Social media"
    ),
    ADGUARD_RU(
        "adguard_ru", "https://filters.adtidy.org/windows/filters/1.txt?id=1",
        "adguard_ru.txt", "AdGuard Russian",
        defaultEnabled = false
    ),
    ADGUARD_TR(
        "adguard_tr", "https://filters.adtidy.org/windows/filters/13.txt?id=13",
        "adguard_tr.txt", "AdGuard Turkish",
        defaultEnabled = false
    ),

    // ── Fanboy lists ─────────────────────────────────────────────────────────
    FANBOY_ANNOY(
        "fanboy_annoy", "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
        "fanboy_annoy.txt", "Fanboy Annoyances",
        defaultEnabled = false                       // AdGuard Annoyances already covers these
    ),
    FANBOY_INDIAN(
        "fanboy_indian", "https://secure.fanboy.co.nz/fanboy-indian.txt",
        "fanboy_indian.txt", "Fanboy Indian",
        defaultEnabled = false
    ),

    // ── anti-adblock ─────────────────────────────────────────────────────────
    ANTIADB_ABP(
        "antiadb_abp", "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt",
        "antiadb_abp.txt", "Anti-Adblock filters (ABP)",
        enforceChecksum = false            // its published header ≠ the standard tooling digest
    ),
    ANTIADB_REEK(
        "antiadb_reek", "https://raw.githubusercontent.com/reek/anti-adblock-killer/master/anti-adblock-killer-filters.txt",
        "antiadb_reek.txt", "Anti-Adblock Killer (Reek)"
    ),
    FUCKFUCK(
        "fuckfuck", "https://raw.githubusercontent.com/bogachenko/fuckfuckadblock/master/fuckfuckadblock.txt",
        "fuckfuck.txt", "Fuck Fuckadblock"
    ),

    // ── big community blocklists ─────────────────────────────────────────────
    YOYO_HOSTS(
        "yoyo_hosts", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=1&mimetype=plaintext",
        "yoyo_hosts.txt", "Peter Lowe ads+trackers (hosts)"
    ),
    OISD_BIG(
        "oisd_big", "https://abp.oisd.nl",
        "oisd_big.txt", "oisd big (233k rules — heavy)",
        defaultEnabled = false
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

    /** short human status per list for the settings UI — Compose state so rows live-update. */
    var statuses by mutableStateOf<Map<ListInfo, String>>(emptyMap())
        private set

    @Volatile
    var loaded: Boolean = false
        private set

    private val staleAfter = Duration.ofDays(7)
    private val initFlag = AtomicBoolean()

    // ── simple persisted settings (shared prefs file with the whole app) ─────

    fun isEnabled(l: ListInfo): Boolean = prefs.getBoolean("fl_${l.id}", l.defaultEnabled)

    /** enabling loads the list (parse from cache/asset) lazily; disabling frees its rules. */
    fun setEnabled(l: ListInfo, enabled: Boolean) {
        prefs.edit().putBoolean("fl_${l.id}", enabled).apply()
        if (!loaded) return
        scope.launch {
            withContext(Dispatchers.IO) {
                if (enabled) {
                    if (net[l] == null) runCatching { readStored(l) }
                } else {
                    net.remove(l)
                    cos.remove(l)
                    setStatus(l, "off — enable to load")
                }
                recompile()
            }
        }
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
                // a killed process can leave half-written "<id>.txt.tmp" updates behind —
                // they are never read (the real cache is "<id>.txt") and only waste space
                runCatching { dir.listFiles { f -> f.name.endsWith(".txt.tmp") }?.forEach { it.delete() } }
                for (l in ListInfo.all) readStored(l)
                loaded = true
                recompile()
            }
            maybeRefresh(scope)
        }
    }

    /**
     * Automatic (on start) refresh — every ENABLED stale/missing list, strictly
     * one at a time with a short gap, then ONE engine rebuild at the end.
     * (Parallel downloads + a recompile per list were the startup OOM/lag:
     * 17 lists × full parse × whole-engine rebuild at once.)
     */
    fun maybeRefresh(scope: CoroutineScope) {
        val todo = ListInfo.all.filter { isEnabled(it) && stale(it) }
        if (todo.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                for (l in todo) {
                    runCatching { doRefresh(l, recompiling = false) }
                    delay(500)             // keep the phone calm between downloads
                }
                runCatching { recompile() }     // one rebuild for the whole batch
            }
        }
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

    private fun doRefresh(l: ListInfo, recompiling: Boolean = true) {
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
        val problem = validate(text, l)
        if (problem != null) {
            setStatus(l, "rejected: $problem")        // corrupted download — cache survives
            return
        }
        val p = try {
            // line sequence — no split array of a multi-MB list in the heap at once
            parseAll(text.lineSequence(), l)
        } catch (t: Throwable) {
            setStatus(l, "parse failed (${t.message?.take(40) ?: "out of memory"})")
            return
        }
        if (!dir.exists()) dir.mkdirs()
        val dst = File(dir, "${l.id}.txt")
        val tmp = File(dir, "${l.id}.txt.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(dst) && !dst.delete() && !tmp.renameTo(dst)) tmp.delete()
        prefs.edit().putLong("fl_${l.id}_ts", System.currentTimeMillis()).apply()
        net[l] = p.first
        cos[l] = p.second
        setStatus(l, "updated · ${p.first.size} rules")
        if (recompiling && loaded) recompile()
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
     * Hebrew and the AdGuard / Fanboy families do) verify it against our own
     * digest of the same text, plus require a sensible rule count. Lists whose
     * published header does not follow the standard tooling (see
     * [ListInfo.enforceChecksum]) skip the digest. A failed check ⇒ the
     * download is rejected and the cached copy keeps being served.
     * Returns a short problem note, or null = accepted.
     */
    fun validate(text: String, l: ListInfo? = null): String? {
        val lines = text.split(Regex("\\r\\n|\\n|\\r"))
        val rules = lines.count { val t = it.trim(); t.isNotEmpty() && !t.startsWith("!") && !t.startsWith("[") }
        if (rules < 40) return "too few rules ($rules)"
        if (l != null && !l.enforceChecksum) return null
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

    /** hosts-file line — `0.0.0.0 domain.tld` / `127.0.0.1 domain.tld` (/ echo lines). */
    private val hostsLine = Regex("""^(?:0\.0\.0\.0|127\.0\.0\.1|::1?)\s+([^#\s]+)""")

    private fun parseAll(lines: Sequence<String>, l: ListInfo): Pair<ArrayList<FilterRule>, ArrayList<CosmeticRule>> {
        val nr = ArrayList<FilterRule>()
        val cr = ArrayList<CosmeticRule>()
        val list = l.displayName
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("!") || t.startsWith("[") || t.startsWith("=")) continue
            // hosts-format lists (Peter Lowe & co.) → the same ||domain^ rule the ABP
            // lists use; the raw "0.0.0.0 domain" text never matches a url by itself
            val rule = hostsLine.find(t)?.let { "||${it.groupValues[1]}^" } ?: t
            if (rule.startsWith("@!") || rule.startsWith("@@") || rule.startsWith("##")) {
                FilterParser.parseCosmetic(rule, list)?.let { cr += it }
            } else {
                FilterParser.parse(rule, list)?.let { nr += it }
            }
        }
        return nr to cr
    }

    /**
     * Loads one list from the cached download or the bundled asset. Only
     * ENABLED lists are ever parsed — a disabled list costs nothing, which is
     * what keeps 21 shipped lists from blowing the heap. Streaming line parse
     * + a Throwable guard per list: one oversized/broken list can never crash
     * or freeze the app start.
     */
    private fun readStored(l: ListInfo) {
        if (!isEnabled(l)) {
            setStatus(l, "off — enable to load")
            return
        }
        try {
            val f = File(dir, "${l.id}.txt")
            val p = if (f.exists()) {
                f.bufferedReader().useLines { lines -> parseAll(lines, l) }
            } else {
                appCtx.assets.open("filters/${l.asset}").bufferedReader().useLines { lines -> parseAll(lines, l) }
            }
            net[l] = p.first
            cos[l] = p.second
            setStatus(l, if (f.exists()) "ready · ${p.first.size} rules" else "bundled fallback · ${p.first.size} rules")
        } catch (t: Throwable) {
            // OOM / corrupt cache / missing asset — that ONE list stays empty, the rest work
            net.remove(l)
            cos.remove(l)
            setStatus(l, "load failed (${t.message?.take(40) ?: "out of memory"})")
        }
    }

    /**
     * Recompiles every enabled list's rules into one fresh engine and swaps it
     * in. Duplicate lines across lists (the blocklists overlap heavily — hosts
     * lists inside oisd, uBO inside EasyList) collapse to ONE rule each, which
     * is the single biggest memory saver at 21 shipped lists.
     */
    fun recompile() {
        if (!loaded) return
        val all = ArrayList<FilterRule>()
        val allCos = ArrayList<CosmeticRule>()
        val seenNet = HashSet<String>()
        val seenCos = HashSet<String>()
        for (l in ListInfo.all) {
            if (!isEnabled(l)) continue
            val n = net[l] ?: continue
            for (r in n) if (seenNet.add(r.raw)) all += r
            cos[l]?.let { c -> for (r in c) if (seenCos.add(r.raw)) allCos += r }
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
