package com.dvora.dvora20.adblock

/** The subset of resource kinds this engine understands for $kind options. */
enum class ResourceKind { SCRIPT, IMAGE, STYLESHEET, XHR, SUBDOCUMENT, FRAME, OBJECT, DOCUMENT, POPUP, WEBSOCKET, OTHER }

object Kinds {
    fun of(name: String): ResourceKind = when (name.trim().lowercase()) {
        "script", "javascript" -> ResourceKind.SCRIPT
        "image", "images", "xml", "svg" -> ResourceKind.IMAGE
        "stylesheet", "css", "font" -> ResourceKind.STYLESHEET
        "xmlhttprequest", "xhr", "ping", "beacon", "fetch" -> ResourceKind.XHR
        "subdocument", "doc" -> ResourceKind.SUBDOCUMENT
        "frame", "iframe", "object" -> ResourceKind.FRAME
        "swf", "flash", "media", "video", "audio" -> ResourceKind.OBJECT
        "document", "mainframe" -> ResourceKind.DOCUMENT
        "popup", "webrtc", "elemhide" -> ResourceKind.POPUP
        "websocket", "ws", "wss" -> ResourceKind.WEBSOCKET    // never matches an http(s) request
        else -> ResourceKind.OTHER
    }
}

/**
 * A single parsed network filter rule.
 *
 * @param raw        original line (trimmed) — shown in the UI block list
 * @param host       anchored host part (`||host` / `|host`); null for bare patterns
 * @param path       pattern text following the host (may contain wildcards), or
 *                   the whole bare pattern when host == null
 * @param exception  true for `@@` whitelist rules
 * @param kinds      matched resource kinds (empty list = matches any kind)
 * @param thirdParty whether a `$third-party` restriction applies
 * @param siteDomains `domain=` / `~domain=` host list (empty = any host)
 * @param negate     true when the site list was given as `~domain=`
 * @param end        true when the pattern carries a trailing `^` anchor
 * @param allowSub   true when the anchor was `||` (sub-domain matches allowed)
 * @param list       name of the list the rule came from (stats / UI)
 *
 * [keyword] is a short literal run chosen at index time; [rx] is the
 * lazily compiled matching regex (simple literal domain rules — the large
 * majority in EasyList — never allocate a regex unless actually needed).
 */
class FilterRule(
    val raw: String,
    val host: String?,
    val path: String,
    val exception: Boolean,
    val kinds: List<ResourceKind>,
    val thirdParty: Boolean,
    val siteDomains: List<String>,
    val negate: Boolean,
    val end: Boolean,
    val allowSub: Boolean,
    val list: String
) {
    /** literal keyword used for hash-index lookup (may be empty → never indexed) */
    var keyword: String = ""
        internal set
    var rx: Regex? = null
        private set

    /** true once the pattern was found uncompilable as a regex — rule skipped forever */
    var dead: Boolean = false
        private set

    /** rolling visited marker so candidate collection never uses a HashSet allocation */
    var visited: Int = 0

    fun pattern(): String = (host ?: "") + path

    fun regex(): Regex? {
        val cached = rx
        if (cached != null) return cached
        if (dead) return null
        val out = StringBuilder()
        if (host != null) {
            out.append("[a-z]{2,6}://")
            if (allowSub) out.append("(?:[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)*\\.)*")
            out.append(hostRx(host))
            out.append("(?=[\\/\\?\\#]|$)")
        }
        if (path.isNotEmpty()) out.append(patternRx(path, end))
        try {
            val r = Regex(out.toString())
            rx = r
            return r
        } catch (e: java.util.regex.PatternSyntaxException) {
            dead = true            // malformed pattern in some list line — never crash on match()
            return null
        }
    }
}

/** A parsed cosmetic (element hiding) rule. */
class CosmeticRule(
    val raw: String,
    val domain: String?,        // null = applies on every page
    val selectors: List<String>,
    val exception: Boolean,     // @@## form (show element despite other rules)
    val list: String
)

// ═══════════════════════════════════════════════════════════════════════════════
// PARSER
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Parses one line of an Adblock-Plus style filter list.
 *
 * Supported: pattern rules (bare / |host / ||host / ||host/path),
 * leading/trailing `^` anchors, `*` `%` `,` `$` wildcards, the `$kind`,
 * `$third-party`, `domain=` and `~domain=` options, and `@@` exceptions.
 * Cosmetic `##…` lines are returned by [parseCosmetic].
 * Unsupported constructs (regex `/…/`, redirects, directives) yield null
 * so they are simply skipped — they never break parsing of neighbouring lines.
 */
object FilterParser {

    fun parse(line: String, list: String): FilterRule? {
        var s = line.trim()
        if (s.isEmpty() ||
            s.startsWith("!") ||   // comment / header line
            s.startsWith("[") ||   // [Adblock Plus …] header
            s.startsWith("=")      // macro
        ) return null

        var exception = false
        if (s.startsWith("@!")) {
            exception = true; s = s.substring(2)
        } else if (s.startsWith("@@")) {
            exception = true; s = s.substring(2)
        }
        when {
            exception && s.startsWith("##") -> return null    // "@@##…" cosmetic exception → parseCosmetic
            !exception && s.startsWith("##") -> return null   // "##…" cosmetic → parseCosmetic
            s.startsWith("#") -> return null                   // #@?# / #!/ / #?… redirects — unsupported
        }

        // split pattern / options at the first unescaped '$'
        var pattern: String
        var opts: String
        val d = findDollar(s)
        if (d < 0) {
            pattern = s; opts = ""
        } else {
            pattern = s.substring(0, d); opts = s.substring(d + 1)
        }

        pattern = pattern.trim()
        if (pattern.startsWith("/") || pattern.startsWith("%")) return null   // regex / %regex% — unsupported
        if (pattern.isEmpty()) return null

        var end = false
        var anchored = false
        var allowSub = false
        var host: String? = null
        var path = pattern

        if (pattern.startsWith("||")) {
            anchored = true; allowSub = true; path = pattern.substring(2)
        } else if (pattern.startsWith("|")) {
            anchored = true; path = pattern.substring(1)
        }
        if (path.endsWith("^")) {
            end = true; path = path.dropLast(1)
        }

        if (anchored) {
            val slash = path.indexOf('/')
            host = (if (slash >= 0) path.substring(0, slash) else path).lowercase()
            path = if (slash >= 0) path.substring(slash) else ""
            if (host.isEmpty()) host = null
        } else {
            path = path.lowercase()
        }
        if (anchored && host.isNullOrEmpty()) return null   // "|^…" style junk

        // options
        val kinds = mutableListOf<ResourceKind>()
        var third = false
        var negate = false
        val sites = mutableListOf<String>()
        for (rawOpt in opts.split(',')) {
            val o = rawOpt.trim()
            when {
                o.isEmpty() -> {}
                o == "third-party" -> third = true
                o == "~third-party" -> {}                    // negative — no restriction needed
                o.startsWith("~domain=") -> {
                    negate = true; sites += splitHosts(o.removePrefix("~domain="))
                }

                o.startsWith("domain=") -> {
                    sites += splitHosts(o.removePrefix("domain="))
                }

                else -> {
                    val k = Kinds.of(o)
                    if (k != ResourceKind.OTHER) kinds += k   // unknown/odd opts ($cookie=…) → ignored
                }
            }
        }

        val rule = FilterRule(
            raw = line.trim(),
            host = host,
            path = path,
            exception = exception,
            kinds = kinds,
            thirdParty = third,
            siteDomains = sites,
            negate = negate,
            end = end,
            allowSub = allowSub,
            list = list
        )
        indexKeyword(rule)
        return rule
    }

    /** Parses a `##domain##selector[,…]` cosmetic line; null when not cosmetic. */
    fun parseCosmetic(line: String, list: String): CosmeticRule? {
        var s = line.trim()
        if (!s.startsWith("##") && !s.startsWith("@!") && !s.startsWith("@@")) return null
        var exception = false
        if (s.startsWith("@!") || s.startsWith("@@")) {
            if (!s.contains("##")) return null
            exception = true
            s = s.substringAfter("##")
        } else {
            s = s.substring(2)   // strip "##"
        }
        var domain: String? = null
        if (s.contains("##")) {
            domain = s.substringBefore("##").trim().lowercase().ifEmpty { null }
            s = s.substringAfter("##")
        }
        var sel = s.trim()
        if (sel.startsWith("/")) return null                    // ##/# regex selector — unsupported
        val ci = sel.indexOf("/*")                               // strip trailing comment
        if (ci >= 0) sel = sel.substring(0, ci)
        sel = sel.removeSuffix("!important").trim()
        val selectors = sel.split(',')
            .map { cleanSelector(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (selectors.isEmpty()) return null
        return CosmeticRule(
            raw = line.trim(),
            domain = domain,
            selectors = selectors,
            exception = exception,
            list = list
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** index position of the first '$' that is not backslash-escaped (simple scan). */
    private fun findDollar(s: String): Int {
        for (i in 0 until s.length) {
            val c = s[i]
            if (c == '$' && (i == 0 || s[i - 1] != '\\')) return i
            if (c == '/' && i >= 2 && s[i - 1] == '\\') continue
        }
        return -1
    }

    private fun splitHosts(s: String): List<String> =
        s.lowercase().split('|').map { it.trim() }.filter { it.isNotEmpty() }

    /** strips control chars and quotes from a selector, keeps it ≤ 200 chars. */
    private fun cleanSelector(s: String): String =
        s.replace(Regex("[\\x00-\\x1F\"]"), "").take(200).trim()

    /**
     * Chooses the indexing keyword: longest literal run inside host+pattern.
     * Falls back to the whole pattern for very short ones.
     */
    private fun indexKeyword(rule: FilterRule) {
        val src = (rule.host ?: "") + rule.path
        var best = ""
        var cur = StringBuilder()
        for (c in src) {
            if (c == '*' || c == '^' || c == '%' || c == '$' || c == ',' || c == '#') {
                if (cur.length > best.length) best = cur.toString()
                cur.setLength(0)
            } else cur.append(c)
        }
        if (cur.length > best.length) best = cur.toString()
        val kw = when {
            best.length >= 3 -> best
            src.length >= 4 -> src.take(6)
            else -> best
        }
        rule.keyword = kw.trim('.')
        if (rule.keyword.length < 3 && kw.length < 3) rule.keyword = kw   // keep even tiny keywords
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FILTER-SYNTAX → REGEX CONVERSION
// ═══════════════════════════════════════════════════════════════════════════════

/** Converts a host-part pattern fragment (`||host`) into regex text. */
internal fun hostRx(h: String): String {
    val b = StringBuilder()
    for (c in h) {
        when {
            c == '*' -> b.append("[a-z0-9\\-.]+?")      // wildcard label (*.co.il …)
            c == '.' -> b.append("\\.")
            c == '-' -> b.append(c)
            else -> when {
                // Java regex rejects '\\g'-style escapes of plain letters — keep them literal
                c.isLetterOrDigit() -> b.append(c)
                else -> {
                    b.append('\\'); b.append(c)
                }
            }
        }
    }
    return b.toString()
}

/** Converts a filter pattern fragment (path / bare pattern) into regex text. */
internal fun patternRx(p: String, end: Boolean): String {
    val b = StringBuilder()
    for (c in p) {
        when (c) {
            '*', '%', ',', '$' -> b.append("[^\\/\\?&#]*")            // sequence ≠ {/, ?, &, #}
            '^', '#' -> b.append("(?:[\\/\\?&#=%\\-\\|\\+\\:\\{\\}])") // separator char
            '.' -> b.append("\\.")
            '-', '/', '_' -> b.append(c)
            else -> when {
                // plain letters/digits stay literal — '\\x' of a letter is illegal in Java regex
                c.isLetterOrDigit() -> b.append(c)
                else -> {
                    b.append('\\'); b.append(c)
                }
            }
        }
    }
    if (end) b.append("(?:[\\/\\?\\#]|$)")   // trailing ^ anchor → url separator or end
    return b.toString()
}
