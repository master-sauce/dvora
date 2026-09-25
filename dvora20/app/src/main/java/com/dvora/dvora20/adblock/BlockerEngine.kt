package com.dvora.dvora20.adblock

import kotlin.collections.ArrayDeque

/**
 * The compiled, match-ready filter engine.
 *
 * Rules of every currently-enabled list are merged into ONE keyword → rules
 * hash index. Each incoming URL is tokenised once, and only rules whose
 * keyword appears in one of the URL tokens are fetched from the index and
 * regex-checked — no linear scan over the rule set, ever.
 *
 * Matching is called from the WebView thread; [probe] markers avoid
 * HashSet allocations while collecting candidate rules.
 */
class BlockerEngine {

    /** keyword → rules sharing that keyword (single shared merged index). */
    private val index: HashMap<String, ArrayList<FilterRule>> = HashMap()

    private val cosmetics = ArrayList<CosmeticRule>()

    var totalRules: Int = 0
        private set
    var totalCosmetic: Int = 0
        private set
    var totalException: Int = 0
        private set

    // ── per-page state ────────────────────────────────────────────────────────
    private val lock = Any()
    val pageLog = ArrayDeque<BlockRecord>()          // this page's blocks (capped)
    val reqLog = ArrayDeque<ReqRecord>()             // EVERY request of this page (capped)

    @Volatile
    var pageBlocks: Int = 0
        private set
    val byDomain = HashMap<String, Int>()            // this page: domain → count
    val byList = HashMap<String, Int>()              // this page: list → count

    @Volatile
    var lifetime: Long = 0                  // total since install (persisted)

    @Volatile
    private var probe = 1

    /** one block event, for the UI list. */
    data class BlockRecord(val url: String, val host: String, val pattern: String, val list: String)

    /** one network request of the page (allowed or blocked), for the resources panel. */
    data class ReqRecord(val url: String, val tag: String, val blocked: Boolean, val rule: String)

    constructor()

    constructor(network: List<FilterRule>, cosmetic: List<CosmeticRule>, lifetime: Long = 0) {
        for (r in network) add(r)
        cosmetics.addAll(cosmetic)
        totalCosmetic = cosmetic.size
        this.lifetime = lifetime
    }

    private fun add(r: FilterRule) {
        val kw = r.keyword
        if (kw.isEmpty()) return
        index.getOrPut(kw) { ArrayList(4) }.add(r)
        if (r.exception) totalException++ else totalRules++
    }

    /** called at every page start — clears the previous page's counters/log. */
    fun newPage() {
        synchronized(lock) {
            pageLog.clear()
            reqLog.clear()
            pageBlocks = 0
            byDomain.clear()
            byList.clear()
        }
    }

    /** every page request lands here (allowed + blocked), capped at 800. */
    fun noteReq(tag: String, url: String, blocked: Boolean, rule: String) {
        synchronized(lock) {
            reqLog.addLast(ReqRecord(url, tag, blocked, rule))
            if (reqLog.size > 800) reqLog.removeFirst()
        }
    }

    /**
     * Decides whether [url] (host [host], resource [kind], document host
     * [docHost]) should be intercepted.
     *
     * @return the first matching non-exception rule to block with, or null
     *         when nothing matched or a matched exception (whitelist) wins.
     */
    fun match(
        url: String, host: String, kind: ResourceKind, docHost: String
    ): FilterRule? {
        if (probe == Int.MAX_VALUE) {                 // marker rollover → reset markers
            probe = 1
            for (rules in index.values) for (r in rules) r.visited = 0
        }
        val mark = probe++;
        val urlLc = url.lowercase()
        val tp = isThirdParty(host, docHost)

        var blocked: FilterRule? = null
        // candidate rules = anything whose keyword is contained in some URL token
        for (key in lookupKeys(urlLc)) {
            val list = index[key] ?: continue
            for (r in list) {
                if (r.visited == mark) continue
                r.visited = mark
                if (r.regex()?.find(urlLc) == null) continue   // null rx = uncompilable rule, skipped
                if (!optionsOk(r, kind, host, tp)) continue
                if (r.exception) return null                // exception rule wins outright
                if (blocked == null) blocked = r
            }
        }
        if (blocked != null) {
            note(host, blocked, url)
            return blocked
        }
        return null
    }

    /** true when the candidate rule's options accept this request. */
    private fun optionsOk(r: FilterRule, kind: ResourceKind, host: String, tp: Boolean): Boolean {
        if (r.thirdParty && !tp) return false
        if (!r.siteDomains.isEmpty()) {
            var inSite = false
            for (d in r.siteDomains) if (host == d || host.endsWith(".$d")) {
                inSite = true; break
            }
            if (r.negate) return !inSite
            if (!inSite) return false
        }
        if (r.kinds.isNotEmpty() && kind !in r.kinds) return false
        return true
    }

    private fun note(host: String, r: FilterRule, url: String) {
        synchronized(lock) {
            pageBlocks++
            byDomain[host] = (byDomain[host] ?: 0) + 1
            byList[r.list] = (byList[r.list] ?: 0) + 1
            lifetime++
            if (pageLog.size < 400) pageLog.addLast(BlockRecord(url, host, r.pattern(), r.list))
        }
    }

    /** all selectors that apply on [host] (global + domain-scoped), deduplicated. */
    fun selectorsFor(host: String): ArrayList<String> {
        val out = ArrayList<String>(64)
        val seen = ArrayList<String>()
        for (c in cosmetics) {
            if (c.exception) continue
            if (hostMatches(c.domain, host)) {
                for (s in c.selectors) {
                    if (!seen.contains(s)) {
                        seen += s; out += s
                        if (out.size >= 400) break
                    }
                }
                if (out.size >= 400) break
            }
        }
        return out
    }

    private fun hostMatches(domain: String?, host: String): Boolean {
        if (domain == null) return true                     // global selector
        return host == domain || host.endsWith(".$domain")
    }

    /**
     * The injection script for the current page: hides the matched selectors
     * now AND keeps re-running through a MutationObserver, so dynamically
     * injected ad elements get removed too. `display:none!important` never
     * disturbs surrounding RTL flow — siblings keep their layout.
     */
    fun cosmeticJs(host: String): String? {
        val sels = selectorsFor(host)
        if (sels.isEmpty()) return null
        val json = sels.joinToString(",") { "\"" + escapeJs(it) + "\"" }
        return """
            (function(){if(window.__dvora_css)return;var S=[$json];var R=S.join(',');
            function h(){try{var n=document.querySelectorAll(R);
            for(var i=0;i<n.length;i++)n[i].style.setProperty('display','none','important');}catch(e){}}
            var mo=new MutationObserver(function(){h();});
            if(document.documentElement)mo.observe(document.documentElement,{childList:true,subtree:true});
            h();window.__dvora_css=true;})();
        """.trimIndent()
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("</", "<\\/")

    // ── keyword lookup tokens ─────────────────────────────────────────────────

    private val SEP = Regex("[\\/\\?&#=%+:\\|{}]")

    /** URL → lookup keys: path/host tokens, their dotted labels and suffixes. */
    private fun lookupKeys(url: String): ArrayList<String> {
        val body = url.substringAfter("://", url)
        val toks = body.split(SEP).filter { it.length in 3..253 }
        val keys = ArrayList<String>(toks.size * 3)
        for (t in toks.take(220)) {
            keys += t
            if ('.' in t) {
                val labels = t.split('.')
                for (lab in labels) if (lab.length in 3..63) keys += lab
                // dotted suffixes: covers ||parent.co.il style keywords for sub-hosts
                if (labels.size >= 2) {
                    for (i in 0 until labels.size - 1) {
                        val combo = labels.subList(i, labels.size).joinToString(".")
                        if (combo.length in 4..100) keys += combo
                    }
                }
            }
        }
        return keys
    }

    // ── third-party detection ─────────────────────────────────────────────────

    companion object {
        const val NONE = 0
        const val BLOCK = 1
        const val ALLOW = 2   // exception matched → allow

        private val MULTI = setOf(
            // classic
            "co.uk", "co.jp", "co.nz", "co.in", "co.kr", "com.au", "com.br",
            "com.cn", "com.hk", "co.za", "co.id", "co.th", "co.il",
            // israeli specifics
            "org.il", "net.il", "ac.il", "gov.il", "muni.il"
        )

        /** registrable (eTLD+1-ish) host without a proper PSL — small suffix table. */
        fun registrable(host: String): String {
            val labels = host.split('.')
            if (labels.size >= 3) {
                val two = labels.takeLast(2).joinToString(".")
                if (two in MULTI) return labels.takeLast(3).joinToString(".")
            }
            return labels.takeLast(2).joinToString(".")
        }

        fun isThirdParty(host: String, docHost: String): Boolean {
            if (host == docHost) return false
            return registrable(host) != registrable(docHost)
        }
    }
}
