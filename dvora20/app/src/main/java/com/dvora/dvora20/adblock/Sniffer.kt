package com.dvora.dvora20.adblock

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The Media sniffer core.
 *
 * Three pieces:
 *  - [SnifferStore]  — the per-page capture list (deduped, cleared on every page start)
 *  - [SniffName]     — filename resolution: Content-Disposition filename* (charset+percent
 *                      decoding) → url filename/title query params → path segment →
 *                      mime-derived extension with wrong-extension correction
 *  - [SnifferJsInterface] — the `JSInterface` bridge installed on the WebView; the injected
 *                      script (the engine's XMLHttpRequest/fetch overrides) calls into it on every
 *                      ajax response so classification sees the REAL response headers and
 *                      body, not just the request url.
 */
data class CapturedMedia(
    val url: String,
    val kind: String,        // m3u8 / mpd / mp4 / webm / audio / pdf / zip / blob / aeskey …
    val mime: String,
    val size: Long,          // bytes, -1 unknown
    val filename: String?,   // resolved name, null when only a guess would be possible
    val referer: String?,    // exact referer the page used for this resource
    val cookie: String?,     // exact Cookie header the page used, when the page set one
    val iv: String? = null,  // HLS AES-128 IV (hex) — present only for kind == "aeskey"
    val duration: Double = -1.0,   // seconds, -1 unknown — #EXTINF sums of a playlist body
    val parts: Int = -1,           // segment count (variant count for masters), -1 unknown
    val finalUrl: String? = null,  // redirect-resolved url (the probe engine follows to the end); the row keeps the original
    // every header the page's own JS set on the request (auth tokens, custom X-*) —
    // the player's extra headers: replayed by probe + both download engines
    val extraHeaders: Map<String, String>? = null,
    val master: Boolean = false,    // m3u8 master (variant list) — THE row to pick for the full video
    val qualities: String? = null   // master's variant ladder "1080p·720p", highest first
)

/** row model for the media picker / badge */
data class SniffRow(
    val url: String,
    val label: String,
    val size: Long,
    val name: String?,
    val isSegment: Boolean,
    val isPlaylist: Boolean,
    val isBlob: Boolean = false,
    val duration: Double = -1.0,
    val parts: Int = -1,
    val isMaster: Boolean = false,
    val qualities: String? = null
)

object SnifferStore {

    /** hard cap on probes per page — keeps pathological pages from hammering the network */
    private const val MAX_PROBES = 30

    private val lock = Any()
    private val captures = LinkedHashMap<String, CapturedMedia>()  // key = lowercase url (no fragment), first-seen order
    private val probed = HashSet<String>()                          // urls already handed to ResourceProbe
    private val headerCache = HashMap<String, Map<String, String>>()  // url → the page's exact request headers

    /** page navigation — drop every capture of the old page; the header cache survives —
     *  downloads often start after the user already moved on to the next page. */
    fun newPage() {
        synchronized(lock) { captures.clear(); probed.clear() }
    }

    /** the url-to-headers cache — the probe must look EXACTLY like the page's own request. */
    fun noteHeaders(url: String, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        synchronized(lock) {
            if (headerCache.size >= 600) headerCache.clear()    // bounded — page caps it anyway
            headerCache[url.lowercase().substringBefore('#')] = headers
        }
    }

    fun headersFor(url: String): Map<String, String>? =
        synchronized(lock) { headerCache[url.lowercase().substringBefore('#')] }

    /** returns false when the url was already captured on this page */
    fun add(c: CapturedMedia): Boolean = synchronized(lock) {
        val key = c.url.lowercase().substringBefore('#')
        if (c.url.isBlank() || key in captures) return false
        captures[key] = c
        true
    }

    /** in-place enrichment — used by ResourceProbe to fill size/mime/filename/kind */
    fun update(url: String, transform: (CapturedMedia) -> CapturedMedia) {
        synchronized(lock) {
            val key = url.lowercase().substringBefore('#')
            val cur = captures[key] ?: return
            captures[key] = transform(cur)
        }
    }

    /**
     * One-shot probe launcher — [launch] runs at most once per url per page (deduped by url,
     * capped at MAX_PROBES). Returns true when it actually fired.
     */
    fun markProbe(url: String, launch: () -> Unit): Boolean {
        synchronized(lock) {
            val key = url.lowercase().substringBefore('#')
            if (key in probed || probed.size >= MAX_PROBES) return false
            probed.add(key)
        }
        return try { launch(); true } catch (_: Exception) { false }
    }

    fun all(): List<CapturedMedia> = synchronized(lock) { captures.values.toList() }

    /** playlists first — the entry point yt-dlp needs for a stream */
    fun playlists(): List<CapturedMedia> = all().filter { it.kind == "m3u8" || it.kind == "mpd" }

    fun byUrl(url: String): CapturedMedia? = synchronized(lock) {
        captures[url.lowercase().substringBefore('#')]
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// URL / MIME classification (the download manager extension sets, trimmed to what browsers hit)
// ═══════════════════════════════════════════════════════════════════════════════

internal object Sniff {

    private val VIDEO_EXT = setOf(
        "divx", "webm", "mkv", "flv", "vob", "ogv", "avi", "mov", "wmv", "yuv", "rm", "rmvb",
        "asf", "amv", "mp4", "mpg", "mp2", "mpeg", "mpe", "mpv", "m2v", "m4v", "svi", "3gp",
        "3g2", "mxf", "roq", "nsv", "f4v", "f4p", "f4a", "f4b", "rv", "wmx", "wvx", "drc", "qt"
    )
    private val AUDIO_EXT = setOf(
        "aac", "aax", "aiff", "amr", "ape", "awb", "dct", "dss", "dvf", "flac", "gsm", "m4a",
        "m4b", "m4p", "mmf", "mp3", "mpc", "msv", "ogg", "oga", "mogg", "opus", "ra", "tta",
        "vox", "wav", "wma"
    )
    private val ARCHIVE_EXT = setOf(
        "7z", "cab", "rar", "tar", "tgz", "zip", "zoo", "deb", "rpm", "gz", "bz2", "xz", "iso",
        "img", "bin", "dmg", "apk", "apks", "apkm", "xapk", "jar", "ipa"
    )
    private val DOC_EXT = setOf(
        "pdf", "doc", "docm", "docx", "dot", "epub", "mobi", "rtf", "txt", "csv", "xls",
        "xlsb", "xlsm", "xlsx", "xlt", "odp", "ods", "odt", "ppt", "pptx"
    )
    private val SUBTITLE_EXT = setOf("srt", "vtt", "sub", "ttml", "sbv", "ssa", "ass")

    /** stream fragments — never standalone captures, they belong to their playlist */
    private val SEGMENT_EXT = setOf("ts", "m4s", "mp4s")

    /** extensionless path with no mime hint → not classified */
    fun kind(url: String, mime: String, body: String?, size: Long): String? {
        try {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            val ext = path.substringAfterLast('.', "")
            val m = mime.lowercase()

            // stream containers first — their url may hide behind extension-less endpoints
            if (path.endsWith(".m3u8") || m.contains("mpegurl")) return "m3u8"
            if (path.endsWith(".mpd") || m.contains("dash+xml")) return "mpd"
            if (body != null) {
                val ts = body.trimStart()
                if (ts.length >= 7 && ts.substring(0, 7).equals("#EXTM3U", ignoreCase = true)) return "m3u8"
            }

            if (ext in SEGMENT_EXT) return null                       // playlist internals
            if (ext in VIDEO_EXT) return ext
            if (ext in AUDIO_EXT) return ext
            if (ext in SUBTITLE_EXT) return ext
            if (ext in ARCHIVE_EXT || ext in DOC_EXT) return ext

            // header-driven (no usable extension in the url)
            when {
                m.startsWith("video/") -> return extForMime(m)?.removePrefix(".") ?: "video"
                m.startsWith("audio/") -> return extForMime(m)?.removePrefix(".") ?: "audio"
                else -> {
                    val me = extForMime(m)?.removePrefix(".")
                    if (me != null && (me in ARCHIVE_EXT || me in DOC_EXT || me in SUBTITLE_EXT)) return me
                }
            }

            // plain html page bodies are never media — but anything else gets a body scan
            if (m.contains("text/html") || m.contains("application/json")) return null
            if (body != null && body.startsWith("<")) return null
            if (size >= 0 && size < 10_240) return null               // the ≥10 KB gate kills beacons/icons
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /** resolve [rel] against [base] — absolute, scheme-relative, root-relative or path-relative */
    fun resolveUrl(base: String, rel: String): String {
        val u = rel.trim()
        if (u.isEmpty()) return base
        if (u.startsWith("http://", true) || u.startsWith("https://", true)) return u
        if (u.startsWith("data:") || u.startsWith("blob:")) return u
        return try {
            val b = URL(base)
            if (u.startsWith("//")) "https:$u"
            else URL(b, u).toString()
        } catch (_: Exception) {
            u
        }
    }

    internal val MIME_EXT = mapOf(
        "video/mp4" to "mp4", "video/webm" to "webm", "video/x-matroska" to "mkv",
        "video/quicktime" to "mov", "video/x-msvideo" to "avi", "video/x-flv" to "flv",
        "video/mpeg" to "mpeg", "video/mp2t" to "ts", "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/flac" to "flac",
        "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/wav" to "wav",
        "audio/x-wav" to "wav", "application/pdf" to "pdf", "application/zip" to "zip",
        "application/x-rar-compressed" to "rar", "application/vnd.android.package-archive" to "apk",
        "application/xapk-package-archive" to "xapk", "application/vnd.apkm" to "apkm",
        "application/epub+zip" to "epub", "text/plain" to "txt", "text/csv" to "csv",
        "text/vtt" to "vtt", "application/x-subrip" to "srt",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/msword" to "doc", "application/vnd.ms-excel" to "xls",
        "application/x-7z-compressed" to "7z", "application/gzip" to "gz",
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp"
    )

    internal fun extForMime(mime: String): String? = MIME_EXT[mime.substringBefore(';').trim()]?.let { ".$it" }

    /**
     * Playlist metrics: the #EXTINF sum is the stream duration and the
     * segment-line count its parts; a master playlist (no #EXTINF yet) returns
     * its variant count instead of parts.
     */
    fun parsePlaylist(body: String): Pair<Double, Int> {
        var dur = 0.0
        var parts = 0
        var variants = 0
        for (line in body.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("#EXTINF:") -> {
                    t.substring(8).substringBefore(',').trim().toDoubleOrNull()?.let { dur += it }
                    parts++
                }
                t.isNotEmpty() && !t.startsWith("#") -> if (parts == 0) variants++
            }
        }
        return if (parts > 0) dur to parts else -1.0 to variants
    }

    // ── the download manager body scanner (Extract's generic half) ────────────────────────────

    /** capture extensions + subtitles — what the body scan looks for. */
    internal val CAPTURE_EXT = Regex(
        """\.(?:m3u8|mpd|mkv|flv|vob|avi|wmv|mp4|mpg|mp2|mpeg|mpe|mpv|m2v|m4v|3gp|3g2|vtt|srt|sub|ttml|sbv)(?![a-z0-9])""",
        RegexOption.IGNORE_CASE
    )

    /** social-share junk — never captured */
    private val JUNK_LINK = Regex(
        """facebook\.com/sharer\.php\?|twitter\.com/share\?|plus\.google\.com/share\?|whatsapp://send\?""",
        RegexOption.IGNORE_CASE
    )

    fun isJunkLink(url: String): Boolean = JUNK_LINK.containsMatchIn(url)

    /**
     * `?url=http…` / `&subtitle=http…` / `?u=https…` → the wrapped media url
     * (the engine's loadResource slice — proxied/aliased media endpoints).
     */
    fun embeddedUrl(url: String): String? {
        val m = Regex("""[?&](?:subtitle|url|u|src|target|file|media|video)=(https?[^&]+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return try {
            java.net.URLDecoder.decode(m.groupValues[1].trim(), "UTF-8")
        } catch (_: Exception) {
            m.groupValues[1].trim()
        }
    }

    /** master playlist → its variant qualities ("1080p·720p"), highest first. */
    fun masterQualities(body: String): String? {
        val hs = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).findAll(body)
            .mapNotNull { it.groupValues[2].toIntOrNull() }.filter { it > 0 }.distinct().sortedDescending().toList()
        if (hs.isNotEmpty()) return hs.joinToString("·") { "${it}p" }
        // no resolutions published — fall back to the bandwidth ladder
        val bws = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).findAll(body)
            .mapNotNull { it.groupValues[1].toLongOrNull() }.filter { it > 0 }.distinct().sortedDescending().take(4).toList()
        return bws.takeIf { it.isNotEmpty() }?.joinToString("·") { "${it / 1000}k" }
    }

    /**
     * `https://<the media's own host>/` — the referer hotlink-protecting CDNs demand
     * (verified: nginx HLS fronts 403 every referer except their own host).
     */
    fun sameHostReferer(url: String): String? = try {
        val h = java.net.URI(url).host ?: return null
        "https://$h/"
    } catch (_: Exception) {
        null
    }

    /**
     * every header the page's JS set on the request
     * JS set on the XHR (setRequestHeader/fetch-init) comes back as a map: auth tokens,
     * custom X-*, API keys. These are the headers the player itself used, so replaying
     * them is what gets past token-checked CDNs that plain referer/cookie can't.
     */
    fun jsonHeaders(json: String?): Map<String, String>? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(json)
            val names = obj.names() ?: return null
            if (names.length() == 0) return null
            val out = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            for (i in 0 until names.length()) {
                val k = names.getString(i)
                val v = obj.get(k)
                // an array-valued header (fetch init) → its first entry (expected behavior)
                out[k] = if (v is JSONArray) v.optString(0) else v.toString()
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * the token around an extension hit: walk back
     * and forward to the nearest delimiter (quote / angle bracket / whitespace),
     * reject anything over 4096 chars, unfold JSON/html escapes, resolve against
     * [base]. [endRef] advances past the closing delimiter so the scan continues.
     */
    fun genericLink(base: String, body: String, start: Int, endRef: java.util.concurrent.atomic.AtomicInteger): String? {
        var s = start
        var i = start - 1
        while (i >= 0) {
            val c = body[i]
            if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '\n' || c == '\t' || c == ' ') {
                s = i + 1
                break
            }
            i--
        }
        var e = endRef.get()
        while (e < body.length) {
            val c = body[e]
            if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '\n' || c == '\t' || c == ' ') break
            e++
        }
        if (e - s > 4096) return null
        endRef.set(e + 1)
        val raw = body.substring(s, e).trim()
            .replace("\\u002F", "/")     // JSON-escaped slash / percent (literal \uXXXX text)
            .replace("\\u002f", "/")
            .replace("\\u0025", "%")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        if (raw.isEmpty()) return null
        return resolveUrl(base, raw)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Filename resolution (condensed filename resolution)
// ═══════════════════════════════════════════════════════════════════════════════

object SniffName {

    /** extensions that are never a real download extension — replaced by the mime-derived one */
    private val BAD_EXT =
        setOf("php", "php3", "php4", "html", "htm", "jsp", "jspx", "asp", "aspx", "axd", "asx",
            "asmx", "ashx", "xhtml", "jhtml", "wss", "do", "action", "pl", "phtml", "py", "rb",
            "rhtml", "shtml", "cgi", "stm")

    /** Content-Disposition — filename*=charset''percent-encoded first (RFC 6266), then filename= */
    fun fromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val raw = m.groupValues[1].trim().removeSuffix(";").trim()
            val parts = raw.split("''", limit = 2)
            val name = if (parts.size == 2) {
                val charset = try { Charset.forName(parts[0].trim()) } catch (_: Exception) { null }
                try {
                    URLDecoder.decode(parts[1].trim(), charset?.name() ?: "UTF-8")
                } catch (_: Exception) {
                    parts[1]
                }
            } else raw
            val n = name.trim().removeSurrounding("\"").trim()
            if (n.isNotEmpty()) return n
        }
        Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val n = m.groupValues[1].trim().removeSurrounding("\"").trim()
            if (n.isNotEmpty()) return n
        }
        return null
    }

    /**
     * Full resolution: disposition → url `filename=` / `title=` query params → path segment →
     * extension correction from mime. Returns null when nothing better than the raw url exists.
     */
    fun resolve(url: String, mime: String, disposition: String?): String? {
        try {
            var name = fromDisposition(disposition)

            if (name == null) {
                // query params of the url (the download manager parses filename / title / mime)
                val query = url.substringAfter('?', "").substringBefore('#')
                for (key in listOf("filename", "title")) {
                    val m = Regex("""[?&]$key=([^&]+)""", RegexOption.IGNORE_CASE).find(query)
                        ?: continue
                    val v = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }
                        .getOrDefault(m.groupValues[1]).trim()
                    if (v.isNotEmpty()) {
                        name = v
                        break
                    }
                }
            }

            if (name == null) {
                var path = url.substringBefore('?').substringBefore('#')
                path = path.substringBefore(';')
                name = path.substringAfterLast('/').trim()
                if (name.isEmpty() || name == "/") name = null
            }

            var n = name ?: return null
            n = runCatching { URLDecoder.decode(n, "UTF-8") }.getOrDefault(n)
                .trim().replace("/", "-").replace("\\", "-")
            if (n.isEmpty()) return null

            // extension correction — wrong/bad ext → mime ext; no ext → append mime ext
            val ext = n.substringAfterLast('.', "").lowercase()
            val mimeExt = Sniff.extForMime(mime)?.removePrefix(".")
            if (n.contains('.')) {
                if (ext in BAD_EXT && mimeExt != null && ext != mimeExt) {
                    n = n.substringBeforeLast('.') + "." + mimeExt
                }
            } else if (mimeExt != null) {
                n = "$n.$mimeExt"
            }
            return n.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            return null
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// JS bridge — the injected script calls these
// ═══════════════════════════════════════════════════════════════════════════════

/** audio kinds the m3u8 child extractor admits as separate capture rows */
private val AUDIO_KINDS = setOf("aac", "mp3", "m4a", "flac", "ogg", "opus", "wav", "wma", "vtt", "srt")

class SnifferJsInterface(
    private val context: Context,
    private val uaProvider: () -> String
) {

    /** every ajax/fetch call — log-only; subresource requests already reach shouldInterceptRequest */
    @JavascriptInterface
    fun logXHR(pageUrl: String, url: String, method: String) {
        // no-op: kept as the bridge surface for the injected script's open() wrapper
    }

    /**
     * Always open — the localhost block-and-capture mode is gone. Kept so the injected
     * script's isAjaxConnectionAllowed call still has a working bridge surface.
     */
    @JavascriptInterface
    fun isAjaxConnectionAllowed(pageUrl: String, url: String): Boolean = true

    /** blob URL recorded by the createObjectURL hook → shown as a "BLOB" row in the picker */
    @JavascriptInterface
    fun logBlob(pageUrl: String, blobUrl: String, mime: String, size: Long) {
        try {
            val kind = Sniff.extForMime(mime)?.removePrefix(".")?.takeIf { it.isNotEmpty() }
                ?: mime.substringAfter('/').takeIf { it.isNotEmpty() && it != "*" }
                ?: "blob"
            SnifferStore.add(
                CapturedMedia(
                    url = blobUrl, kind = if (kind == "blob") "blob" else kind,
                    mime = mime, size = size, filename = null,
                    referer = pageUrl.takeIf { it.startsWith("http") }, cookie = null
                )
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Streamed chunk of a blob: URL — the JS side base64-encodes the in-memory Blob and ships it
     * in 384KB chunks (chunk length is a multiple of 4 so each decodes independently; only the
     * last chunk may carry '=' padding, which Android Base64 handles). The final chunk closes
     * the temp file and atomically moves it to the user's download folder + toasts completion.
     */
    @JavascriptInterface
    fun saveBlobChunk(blobUrl: String, index: Int, total: Int, base64: String) {
        BlobSaver.append(blobUrl, index, total, base64, context, uaProvider())
    }

    /**
     * The classifier hook — fired on every ajax/fetch response with its real headers + body.
     * HLS playlist handling behind the JS bridge.
     */
    @JavascriptInterface
    fun handleM3u8Url(
        method: String?, url: String?, responseUrl: String?, body: String?,
        contentType: String?, contentDisposition: String?, contentLength: String?,
        contentRange: String?, pageUrl: String?, headersJson: String?, source: String?
    ) {
        try {
            if (url.isNullOrBlank() || pageUrl.isNullOrBlank()) return
            val resolved = Sniff.resolveUrl(
                responseUrl?.takeIf { it.isNotBlank() } ?: pageUrl, url
            )
            if (resolved.startsWith("data:") || resolved.startsWith("blob:")) return
            if (resolved.equals(pageUrl, ignoreCase = true)) return

            val mime = contentType?.substringBefore(';')?.trim() ?: ""
            val len = contentLength?.trim()?.toLongOrNull() ?: -1L

            var kind = Sniff.kind(resolved, mime, null, len)

            // body-driven detection — the web already downloaded these bytes; never refetch
            val b = body ?: ""
            if (kind == null && b.isNotEmpty()) {
                val trimmed = b.trimStart()
                if (trimmed.length >= 7 && trimmed.substring(0, 7).equals("#EXTM3U", true)) kind = "m3u8"
            }

            var cookie: String? = null
            var referer: String? = null
            var extraHeaders: Map<String, String>? = null
            if (!headersJson.isNullOrBlank()) {
                runCatching {
                    val obj = JSONObject(headersJson)
                    for (k in obj.keys()) {
                        when (k.lowercase()) {
                            "cookie" -> cookie = obj.optString(k)
                            "referer", "referrer" -> referer = obj.optString(k)
                        }
                    }
                }
                // the engine's getAdditionalHeadersMapFromJavascriptJsonHeaders — EVERY header the
                // player itself set (tokens, authorization, custom X-*) is captured and
                // replayed later; cookie/referer ride in their own fields
                extraHeaders = Sniff.jsonHeaders(headersJson)
                    ?.filterKeys { it.lowercase() !in setOf("cookie", "referer", "referrer") }
                    ?.takeIf { it.isNotEmpty() }
            }

            // the generic extractor — ANY body over 10 chars that isn't itself media gets scanned
            // for embedded media urls: html fragments, json player configs, frame pages
            if (b.length > 10 && kind != "m3u8" &&
                !mime.startsWith("video/") && !mime.startsWith("audio/") && !mime.startsWith("image/")
            ) {
                scanBody(b, resolved, pageUrl, referer, cookie)
            }

            if (kind == null) return
            if (kind !in setOf("m3u8", "mpd") && len in 1..10_239) return   // size gate

            // playlist bodies carry their own metrics — #EXTINF sum = duration, line count = parts;
            // a master (#EXT-X-STREAM-INF) is THE entry point and carries its variant ladder
            var duration = -1.0
            var parts = -1
            var master = false
            var qualities: String? = null
            if (kind == "m3u8" && b.isNotEmpty()) {
                val pd = Sniff.parsePlaylist(b)
                if (pd.second > 0) {
                    parts = pd.second
                    if (pd.first > 0) duration = pd.first
                }
                if (b.contains("#EXT-X-STREAM-INF", true)) {
                    master = true
                    qualities = Sniff.masterQualities(b)
                }
            }

            val cap = CapturedMedia(
                url = resolved,
                kind = kind,
                mime = mime,
                size = len,
                filename = SniffName.resolve(resolved, mime, contentDisposition),
                referer = referer,
                cookie = cookie,
                duration = duration,
                parts = parts,
                extraHeaders = extraHeaders,
                master = master,
                qualities = qualities
            )
            if (SnifferStore.add(cap)) {
                // playlists get their children captured too (master → media/audio/subtitle tracks)
                if (kind == "m3u8" && b.isNotEmpty()) extractM3u8Children(b, resolved, pageUrl, referer, cookie)
                // a probe re-requests with the page's own headers — fills size/mime/filename after redirects
                SnifferStore.markProbe(resolved) { ResourceProbe.probeAsync(resolved, referer ?: pageUrl, cookie, uaProvider()) }
            }
        } catch (_: Throwable) {
        }
    }

    /** m3u8 body → child playlists + per-attribute URI="…" tracks + AES-128 keys (the generic extractor) */
    private fun extractM3u8Children(body: String, baseUrl: String, pageUrl: String, referer: String?, cookie: String?) {
        try {
            // #EXT-X-KEY:METHOD=AES-128,URI="…",IV=0x…  →  captured as kind "aeskey" with iv
            for (m in Regex("""#EXT-X-KEY:.*?METHOD=AES-128.*?URI="([^"]+)"""", RegexOption.IGNORE_CASE).findAll(body)) {
                val keyUrl = Sniff.resolveUrl(baseUrl, m.groupValues[1])
                val iv = Regex("""IV=0x([0-9a-fA-F]+)""", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)
                SnifferStore.add(
                    CapturedMedia(
                        url = keyUrl, kind = "aeskey", mime = "application/octet-stream",
                        size = -1, filename = null, referer = referer ?: pageUrl, cookie = cookie, iv = iv
                    )
                )
            }
            for (line in body.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val child = Sniff.resolveUrl(baseUrl, t)
                val k = Sniff.kind(child, "", null, -1)
                if (k != null && (k == "m3u8" || k == "mpd" || k in AUDIO_KINDS)) {
                    if (SnifferStore.add(CapturedMedia(child, k, "", -1, SniffName.resolve(child, "", null), pageUrl, cookie))) {
                        // audio/subtitle variant tracks get a probe too so the row shows real size
                        SnifferStore.markProbe(child) { ResourceProbe.probeAsync(child, pageUrl, cookie, uaProvider()) }
                    }
                }
            }
            // attribute URIs: #EXT-X-MEDIA / #EXT-X-STREAM-INF … URI="..."
            Regex("""URI="([^"]+)"""").findAll(body).forEach { m ->
                val child = Sniff.resolveUrl(baseUrl, m.groupValues[1])
                val k = Sniff.kind(child, "", null, -1)
                if (k != null && k != "ts" && k != "aeskey") {
                    if (SnifferStore.add(CapturedMedia(child, k, "", -1, SniffName.resolve(child, "", null), pageUrl, cookie))) {
                        SnifferStore.markProbe(child) { ResourceProbe.probeAsync(child, pageUrl, cookie, uaProvider()) }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** html/json body scan — absolute media urls only (conservative; the download manager uses Jsoup on the same idea) */
    /**
     * the generic extractor: the capture-extension pattern runs over the
     * WHOLE body and every hit becomes the token around it (the engine's getGenericVideoLink:
     * relative urls resolve, JSON/html escapes unfold); `<video>/<source>/<audio>`
     * src attributes are scanned too — media elements never touch XHR — and one
     * level of iframe/frame targets gets fetched and scanned again.
     */
    private fun scanBody(body: String, baseUrl: String, pageUrl: String, referer: String?, cookie: String?, depth: Int = 0) {
        try {
            val b = if (body.length > 250_000) body.substring(0, 250_000) else body
            // 1) extension-pattern hits anywhere in the body
            val endRef = java.util.concurrent.atomic.AtomicInteger(0)
            for (m in Sniff.CAPTURE_EXT.findAll(b)) {
                endRef.set(m.range.last + 1)
                val link = Sniff.genericLink(baseUrl, b, m.range.first, endRef) ?: continue
                captureScanned(link, pageUrl, referer, cookie)
                // the download manager: a link ending in "?a=view" also captures its bare form
                if (link.endsWith("?a=view", true)) {
                    captureScanned(link.substring(0, link.length - 7), pageUrl, referer, cookie)
                }
            }
            // 2) media element src attributes — <video>/<source>/<audio> never go through XHR
            for (m in Regex("""<(?:video|source|audio)\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(b)) {
                captureScanned(Sniff.resolveUrl(baseUrl, m.groupValues[1]), pageUrl, referer, cookie)
            }
            // 3) iframe/frame src — fetch the frame's own html and scan it too (one level)
            if (depth == 0) {
                for (m in Regex("""<(?:iframe|frame)\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(b)) {
                    val link = Sniff.resolveUrl(baseUrl, m.groupValues[1])
                    if (!link.startsWith("http") || link.equals(pageUrl, true) || link.equals(baseUrl, true)) continue
                    ResourceProbe.fetchBodyAsync(link, referer ?: pageUrl, cookie, uaProvider(), SnifferStore.headersFor(link)) { html ->
                        if (html != null) scanBody(html, link, pageUrl, referer, cookie, 1)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** one scanned candidate — junk-filtered, classified, stored, probed. */
    private fun captureScanned(url: String, pageUrl: String, referer: String?, cookie: String?) {
        try {
            if (url.isBlank() || !url.startsWith("http")) return
            if (Sniff.isJunkLink(url)) return
            // proxied/wrapped media (proxy.php?u=https://cdn/x.mp4) — the wrapped url is the media
            Sniff.embeddedUrl(url)?.let { if (it != url && it.length < url.length) captureScanned(it, pageUrl, referer, cookie) }
            val k = Sniff.kind(url, "", null, -1) ?: return
            if (SnifferStore.add(
                    CapturedMedia(
                        url = url, kind = k, mime = "", size = -1,
                        filename = SniffName.resolve(url, "", null),
                        referer = referer ?: pageUrl, cookie = cookie
                    )
                )
            ) {
                SnifferStore.markProbe(url) { ResourceProbe.probeAsync(url, referer ?: pageUrl, cookie, uaProvider()) }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * The page's own DOM — pushed at page end (outerHTML) so `<video>`/`<source>`
     * elements and iframes that never issue an XHR still land in the capture list.
     */
    @JavascriptInterface
    fun scanPage(html: String?, pageUrl: String?) {
        try {
            if (html.isNullOrBlank() || pageUrl.isNullOrBlank()) return
            if (!pageUrl.startsWith("http")) return
            scanBody(html, pageUrl, pageUrl, pageUrl, null)
        } catch (_: Throwable) {
        }
    }

    companion object {

        /**
         * Injected on every page start (idempotent — the `realOpen`/`realFetch` guards make
         * re-injection a no-op). The AJAX override script: wraps
         * XMLHttpRequest + fetch so every response's headers and body reach native exactly once.
         */
        val SCRIPT = """
            (function() {
              if (typeof window.realOpen !== 'undefined' && window.realOpen !== null) return;
              window.XMLHttpRequest.prototype.wrappedSetRequestHeader = window.XMLHttpRequest.prototype.setRequestHeader;
              window.XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                this.wrappedSetRequestHeader(header, value);
                if (!this.headers) this.headers = {};
                this.headers[header] = value;
              };
              window.realOpen = window.XMLHttpRequest.prototype.open;
              window.XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
                try { window.JSInterface.logXHR(window.location.href, url, method); } catch (e1) {}
                if (window.JSInterface.isAjaxConnectionAllowed(window.location.href, url)) {
                  this.addEventListener('load', function() {
                    try {
                      var h = this.getAllResponseHeaders().toLowerCase();
                      // body cap — shipping a huge response over the bridge can kill the app;
                      // responseText also throws for non-text responseTypes, headers still matter
                      var rb = '';
                      try { rb = this.responseText || ''; } catch (eB) { rb = ''; }
                      if (rb.length > 2000000) rb = rb.substring(0, 2000000);
                      window.JSInterface.handleM3u8Url(
                        method, url, this.responseURL, rb,
                        h.indexOf('content-type') >= 0 ? this.getResponseHeader('content-type') : '',
                        h.indexOf('content-disposition') >= 0 ? this.getResponseHeader('content-disposition') : '',
                        h.indexOf('content-length') >= 0 ? this.getResponseHeader('content-length') : '',
                        h.indexOf('content-range') >= 0 ? this.getResponseHeader('content-range') : '',
                        window.location.href,
                        this.headers === undefined || this.headers === null ? '' : JSON.stringify(this.headers),
                        'XMLHttpRequest');
                    } catch (e2) {}
                  });
                  return window.realOpen.apply(this, arguments);
                }
                // blocked-but-captured: reroute to a localhost url carrying the original —
                // shouldInterceptRequest decodes it, logs it, returns an empty CORS response
                arguments[1] = 'https://localhost:' + (10000 + Math.floor(Math.random() * 54000)) + '?url=' + btoa(url);
                return window.realOpen.apply(this, arguments);
              };
              window.realFetch = window.fetch;
              function report(response, resource, method, init, data) {
                try {
                  var rUrl = typeof resource === 'string' ? resource : (resource && resource.url ? resource.url : String(resource));
                  if (data && data.length > 2000000) data = data.substring(0, 2000000);   // bridge-size cap
                  window.JSInterface.handleM3u8Url(
                    method, rUrl, response.url, data,
                    response && response.headers ? (response.headers.get('content-type') || '') : '',
                    response && response.headers ? (response.headers.get('content-disposition') || '') : '',
                    response && response.headers ? (response.headers.get('content-length') || '') : '',
                    response && response.headers ? (response.headers.get('content-range') || '') : '',
                    window.location.href,
                    init && init.headers ? JSON.stringify(init.headers) : '', 'fetch');
                } catch (e3) {}
              }
              window.fetch = async function(resource, init) {
                var method = init && init.method ? init.method : 'GET';
                var rUrl = typeof resource === 'string' ? resource : (resource && resource.url ? resource.url : String(resource));
                try { window.JSInterface.logXHR(window.location.href, rUrl, method); } catch (e4) {}
                if (window.JSInterface.isAjaxConnectionAllowed(window.location.href, rUrl)) {
                  var response = await window.realFetch.apply(this, arguments);
                  try {
                    if (!response._modified) {
                      var originalText = response.text.bind(response);
                      var originalJson = response.json.bind(response);
                      report(response, resource, method, init, '');
                      response.text = async function() {
                        var textData = await originalText();
                        if (!response.textHandled) { report(response, resource, method, init, textData); response.textHandled = true; }
                        return textData;
                      };
                      response.json = async function() {
                        var jsonData = await originalJson();
                        if (!response.jsonHandled) { report(response, resource, method, init, jsonData ? JSON.stringify(jsonData) : ''); response.jsonHandled = true; }
                        return jsonData;
                      };
                      Object.defineProperty(response, '_modified', { value: true });
                    }
                  } catch (e5) {}
                  return response;
                }
                // blocked-but-captured fetch
                return window.realFetch.call(this, 'https://localhost:' + (10000 + Math.floor(Math.random() * 54000)) + '?url=' + btoa(rUrl), init);
              };
              // createObjectURL hook — in-memory media blobs become downloadable rows
              (function() {
                if (window.__dvoraBlobHooked) return;
                window.__dvoraBlobHooked = true;
                window.__dvoraBlobs = {};
                var realCreate = URL.createObjectURL;
                URL.createObjectURL = function(obj) {
                  var u = realCreate(obj);
                  try {
                    if (obj && typeof obj.size === 'number' && obj.size >= 10240 && obj.type &&
                        (obj.type.indexOf('video/') === 0 || obj.type.indexOf('audio/') === 0 ||
                         obj.type.indexOf('application/pdf') === 0 || obj.type.indexOf('application/zip') === 0)) {
                      window.__dvoraBlobs[u] = obj;
                      window.JSInterface.logBlob(window.location.href, u, obj.type, obj.size);
                    }
                  } catch (e6) {}
                  return u;
                };
                window.__dvoraSaveBlob = function(blobUrl) {
                  var obj = window.__dvoraBlobs[blobUrl];
                  if (!obj) return 'gone';
                  return new Promise(function(resolve) {
                    try {
                      var fr = new FileReader();
                      fr.onload = function() {
                        try {
                          var s = String(fr.result);
                          var b64 = s.substring(s.indexOf(',') + 1);
                          var CH = 393216;     // 384KB base64 per bridge call — a multiple of 4
                          var total = Math.ceil(b64.length / CH);
                          for (var i = 0; i < total; i++)
                            window.JSInterface.saveBlobChunk(blobUrl, i, total, b64.substring(i * CH, (i + 1) * CH));
                          resolve('sent');
                        } catch (e7) { resolve('err'); }
                      };
                      fr.onerror = function() { resolve('err'); };
                      fr.readAsDataURL(obj);
                    } catch (e8) { resolve('err'); }
                  });
                };
              })();
            })();
        """.trimIndent()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BlobSaver — reassembles base64-chunked blob: bytes and writes the file to the
// download folder. Lives on a single background thread; the JS bridge thread only
// enqueues work onto it so a slow disk never blocks the page.
// ═══════════════════════════════════════════════════════════════════════════════

object BlobSaver {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dvora-blob").apply { isDaemon = true } }
    private val parts = HashMap<String, Array<String?>>()   // blobUrl → decoded-byte-awaiting chunks

    fun append(blobUrl: String, index: Int, total: Int, base64: String, context: Context, userAgent: String) {
        exec.execute {
            try {
                val arr = parts.getOrPut(blobUrl) { arrayOfNulls(total) }
                arr[index] = base64
                if (arr.count { it != null } < total) return@execute          // wait for the rest
                parts.remove(blobUrl)
                val out = java.io.ByteArrayOutputStream()
                for (chunk in arr) {
                    if (chunk == null) return@execute                         // shouldn't happen — guard
                    out.write(Base64.decode(chunk, Base64.DEFAULT))
                }
                val bytes = out.toByteArray()
                val dir = File(
                    context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
                        .getString("yt_dir", null)?.takeIf { it.isNotBlank() }
                        ?: File(Environment.getExternalStorageDirectory(), "Download/dvora").path
                ).also { it.mkdirs() }
                val name = "dvora-blob-${System.currentTimeMillis()}.bin"
                FileOutputStream(File(dir, name)).use { it.write(bytes) }
                SnifferStore.update(blobUrl) { it.copy(filename = name, size = bytes.size.toLong()) }
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        Toast.makeText(context, com.dvora.dvora20.R.string.yt_done, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Throwable) {
                parts.remove(blobUrl)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ResourceProbe — HttpURLConnection ranged GET that fills real size / mime /
// filename after redirects, replaying the page's own referer / cookie / UA.
// This uses OkHttp on a per-tab executor; HttpURLConnection needs no
// extra dependency and stops reading after headers.
// ═══════════════════════════════════════════════════════════════════════════════

object ResourceProbe {
    data class Result(
        val finalUrl: String, val mime: String, val size: Long, val disposition: String?,
        val duration: Double, val parts: Int,
        val master: Boolean, val qualities: String?
    )

    private val exec = Executors.newFixedThreadPool(3) { r -> Thread(r, "dvora-probe").apply { isDaemon = true } }

    fun probeAsync(url: String, referer: String?, cookie: String?, ua: String? = null) {
        exec.execute {
            // the probe engine replays the page's request headers AND the player's own JS-set
            // headers (tokens) — the JS-set ones win, they are what the media host accepted
            val capHeaders = SnifferStore.byUrl(url)?.extraHeaders
            val cached = if (capHeaders != null)
                (SnifferStore.headersFor(url) ?: emptyMap()) + capHeaders
            else SnifferStore.headersFor(url)
            val r = runCatching { probe(url, referer, cookie, ua, cached) }.getOrNull() ?: return@execute
            val existing = SnifferStore.byUrl(url)
            if (existing != null) {
                SnifferStore.update(url) { c ->
                    val mime = r.mime.ifBlank { c.mime }
                    val kind = Sniff.kind(r.finalUrl, mime, null, r.size) ?: c.kind
                    c.copy(
                        mime = mime,
                        size = if (r.size > 0) r.size else c.size,
                        filename = SniffName.resolve(r.finalUrl, mime, r.disposition) ?: c.filename,
                        kind = kind,
                        duration = if (r.duration > 0) r.duration else c.duration,
                        parts = if (r.parts > 0) r.parts else c.parts,
                        // the probe engine resolves redirects to the END — the engine gets the final url
                        finalUrl = if (!r.finalUrl.equals(url, ignoreCase = true)) r.finalUrl else c.finalUrl,
                        master = c.master || r.master,
                        qualities = r.qualities ?: c.qualities
                    )
                }
            } else {
                // reqLog-derived rows are not in the store — promote them to full captures with
                // real size / name / duration (the row keeps its url so the dedup stays clean)
                val kind = Sniff.kind(r.finalUrl, r.mime, null, r.size) ?: return@execute
                if (r.size in 1..10_239 && kind != "m3u8" && kind != "mpd") return@execute   // ≥10KB gate
                SnifferStore.add(
                    CapturedMedia(
                        url = url, kind = kind, mime = r.mime, size = r.size,
                        filename = SniffName.resolve(r.finalUrl, r.mime, r.disposition),
                        referer = referer, cookie = cookie,
                        duration = r.duration, parts = r.parts,
                        finalUrl = if (!r.finalUrl.equals(url, ignoreCase = true)) r.finalUrl else null,
                        master = r.master,
                        qualities = r.qualities
                    )
                )
            }
        }
    }

    /**
     * the probe engine stack: OkHttp — HTTP/2 + a modern TLS fingerprint. Gateways that
     * 403/502 HttpURLConnection and python-urllib traffic accept OkHttp — the exact
     * reason the probe engine probe succeeds where plain-Java requests don't.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** the page's exact request headers (urlHeadersCache) + our Range/UA/Referer/Cookie. */
    private fun buildReq(
        url: String, referer: String?, cookie: String?, range: String?, ua: String?,
        cached: Map<String, String>?
    ): Request {
        val b = Request.Builder().url(url)
        ua?.let { b.header("User-Agent", it) }
        referer?.let { b.header("Referer", it) }
        cookie?.let { b.header("Cookie", it) }
        // the page's EXACT request headers OVERRIDE our guesses — the cached Referer is
        // the one the WebView actually sent and the server accepted. Accept-Encoding
        // stays out — OkHttp negotiates its own and transparently unzips.
        cached?.forEach { (k, v) ->
            if (!k.equals("Range", true) && !k.equals("Host", true) &&
                !k.equals("Accept-Encoding", true) && !k.equals("Content-Length", true) &&
                v.isNotEmpty()
            ) b.header(k, v)
        }
        range?.let { b.header("Range", it) }
        return b.build()
    }

    private fun call(
        url: String, referer: String?, cookie: String?, range: String?, ua: String?,
        cached: Map<String, String>?
    ): Response? = runCatching { http.newCall(buildReq(url, referer, cookie, range, ua, cached)).execute() }.getOrNull()

    /** page/frame fetch for the body scanner — capped at 256KB, replaying the page's headers. */
    fun fetchBodyAsync(url: String, referer: String?, cookie: String?, ua: String?, cached: Map<String, String>? = null, cb: (String?) -> Unit) {
        exec.execute {
            val body = runCatching {
                call(url, referer, cookie, null, ua, cached)?.use { r ->
                    if (r.code !in 200..299) return@use null
                    val mime = (r.header("Content-Type") ?: "").substringBefore(';').trim().lowercase()
                    if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/")) {
                        return@use null
                    }
                    String(readCapped(r, 262_144), Charsets.UTF_8)
                }
            }.getOrNull()
            cb(body)
        }
    }

    /** reads at most [cap] bytes — the parse window; closing the response kills the stream. */
    private fun readCapped(r: Response, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        runCatching {
            r.body?.byteStream()?.use { ins ->
                val buf = ByteArray(8192)
                while (out.size() < cap) {
                    val n = ins.read(buf, 0, minOf(buf.size, cap - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        return out.toByteArray()
    }

    private fun probe(url: String, referer: String?, cookie: String?, ua: String?, cached: Map<String, String>?): Result? {
        if (!url.startsWith("http")) return null
        val path = url.substringBefore('?').lowercase()
        val looksPlaylist = path.endsWith(".m3u8") || path.endsWith(".mpd")
        // playlists → full body (#EXTINF lines run through the whole file); files → a 64KB head window.
        // A 403 here = the hotlink gate (referer must be the media's own host) — retry once
        var ref = referer
        var r = call(url, ref, cookie, if (looksPlaylist) null else "bytes=0-65535", ua, cached)
        if (r == null || r.code == 403) {
            val alt = Sniff.sameHostReferer(url)
            if (alt != null && !alt.equals(ref, true)) {
                r?.close()
                r = call(url, alt, cookie, if (looksPlaylist) null else "bytes=0-65535", ua, cached)
                if (r != null && r.code in 200..399) ref = alt
            }
        }
        r = r ?: return null
        try {
            var code = r.code
            if (code < 200 || code >= 400) return null
            var mime = (r.header("Content-Type") ?: "").substringBefore(';').trim()
            val disp = r.header("Content-Disposition")
            var size = r.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            // 206 partial → Content-Range "bytes 0-65535/12345678" carries the full size
            r.header("Content-Range")?.let { cr ->
                Regex("""bytes\s+\S+/(\d+)""", RegexOption.IGNORE_CASE).find(cr)?.let {
                    size = it.groupValues[1].toLongOrNull() ?: size
                }
            }
            var finalUrl = r.request.url.toString()
            // an extension-less playlist endpoint asked with a Range — reconnect for the whole body
            if (!looksPlaylist && (mime.contains("mpegurl", true) || mime.contains("dash+xml", true))) {
                r.close()
                r = call(finalUrl, ref, cookie, null, ua, cached) ?: return null
                code = r.code
                if (code < 200 || code >= 400) return null
                mime = (r.header("Content-Type") ?: "").substringBefore(';').trim().ifBlank { mime }
                finalUrl = r.request.url.toString()
            }
            var duration = -1.0
            var parts = -1
            var master = false
            var qualities: String? = null
            val fPath = finalUrl.substringBefore('?').lowercase()
            if (looksPlaylist || fPath.endsWith(".m3u8") || fPath.endsWith(".mpd") ||
                mime.contains("mpegurl", true) || mime.contains("dash+xml", true)
            ) {
                val body = String(readCapped(r, 524_288), Charsets.UTF_8)
                val pd = Sniff.parsePlaylist(body)
                if (pd.second > 0) {
                    parts = pd.second
                    if (pd.first > 0) duration = pd.first
                }
                // a master playlist (#EXT-X-STREAM-INF) is THE entry point — flag it + its ladder
                if (body.contains("#EXT-X-STREAM-INF", true)) {
                    master = true
                    qualities = Sniff.masterQualities(body)
                }
            } else {
                val head = readCapped(r, 65_536)
                val trimmedHead = String(head, Charsets.UTF_8).trimStart()
                if (trimmedHead.length >= 7 && trimmedHead.substring(0, 7).equals("#EXTM3U", true)) {
                    // extension-less endpoint answering with a playlist body under a text/plain
                    // mime — refetch the whole body and parse it like the playlist branch
                    r.close()
                    call(finalUrl, ref, cookie, null, ua, cached)?.use { c2 ->
                        if (c2.code in 200..299) {
                            mime = (c2.header("Content-Type") ?: "").substringBefore(';').trim()
                                .ifBlank { "application/vnd.apple.mpegurl" }
                            val body = String(readCapped(c2, 524_288), Charsets.UTF_8)
                            val pd = Sniff.parsePlaylist(body)
                            if (pd.second > 0) {
                                parts = pd.second
                                if (pd.first > 0) duration = pd.first
                            }
                            if (body.contains("#EXT-X-STREAM-INF", true)) {
                                master = true
                                qualities = Sniff.masterQualities(body)
                            }
                        }
                    }
                } else {
                    val kind = Sniff.kind(finalUrl, mime, null, size) ?: ""
                    if (MediaMeta.handles(kind) || mime.startsWith("video/") || mime.startsWith("audio/")) {
                        duration = MediaMeta.duration(kind, mime, head, size) { range ->
                            // mp4 with the moov box at the end — the tail window carries mvhd
                            runCatching {
                                call(finalUrl, ref, cookie, range, ua, cached)?.use { c3 ->
                                    if (c3.code in 200..299) readCapped(c3, 262_144) else ByteArray(0)
                                } ?: ByteArray(0)
                            }.getOrDefault(ByteArray(0))
                        }
                    }
                }
            }
            return Result(finalUrl, mime, size, disp, duration, parts, master, qualities)
        } catch (_: Exception) {
            return null
        } finally {
            r.close()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MediaMeta — duration (seconds) from a container's own header bytes, the download manager-style,
// for every captured url. Only the head/tail window of the file is ever fetched.
// ═══════════════════════════════════════════════════════════════════════════════

internal object MediaMeta {

    private val MP4_KINDS = setOf(
        "mp4", "m4v", "m4a", "m4b", "m4p", "mov", "3gp", "3g2",
        "f4v", "f4p", "f4a", "f4b", "mxf", "qt"
    )
    private val WEBM_KINDS = setOf("webm", "mkv")

    /** true when a duration can be parsed from the header bytes of this kind. */
    fun handles(kind: String): Boolean = kind in MP4_KINDS || kind in WEBM_KINDS || kind == "mp3"

    /**
     * duration in seconds or -1. [tail] lazily fetches a window of the file's end —
     * only used for mp4s whose moov box sits at the end of the file.
     */
    fun duration(
        kind: String, mime: String, head: ByteArray, totalSize: Long,
        tail: (String) -> ByteArray
    ): Double {
        val m = mime.lowercase()
        return when {
            kind in MP4_KINDS || m.startsWith("video/mp4") || m == "audio/mp4" ||
                m.contains("isoformat") || m.startsWith("video/quicktime") -> {
                val d = mp4(head) ?: if (totalSize > 262_144) {
                    val from = totalSize - 262_144
                    mp4(tail("bytes=$from-${totalSize - 1}"))
                } else null
                d ?: -1.0
            }
            kind in WEBM_KINDS || m.startsWith("video/webm") || m.contains("matroska") ->
                webm(head) ?: -1.0
            kind == "mp3" || m == "audio/mpeg" -> mp3(head, totalSize)
            else -> -1.0
        }
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** ISO-BMFF — scan the window for mvhd; duration = duration ÷ timescale. */
    private fun mp4(b: ByteArray): Double? {
        val needle = byteArrayOf('m'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())
        val i = indexOf(b, needle).takeIf { it >= 0 } ?: return null
        val p = i + 4                                   // version/flags payload start
        if (p + 20 > b.size) return null
        return if (b[p].toInt() == 1) {                 // version 1 — 64-bit fields
            if (p + 32 > b.size) return null
            val ts = be32(b, p + 20)
            val dur = be64(b, p + 24)
            if (ts > 0) dur.toDouble() / ts else null
        } else {
            val ts = be32(b, p + 12)
            val dur = be32(b, p + 16)
            if (ts > 0) dur.toDouble() / ts else null
        }
    }

    /** Matroska/WebM — EBML walk: Segment → Info → TimestampScale + Duration. */
    private fun webm(b: ByteArray): Double? {
        var pos = 0
        fun vint(keepMarker: Boolean): Long {
            if (pos >= b.size) return -1L
            val first = b[pos].toInt() and 0xFF
            if (first == 0) return -1L
            var len = 1
            while (len <= 8 && (first and (0x80 shr (len - 1))) == 0) len++
            if (len > 8 || pos + len > b.size) return -1L
            var v = if (keepMarker) first.toLong() else (first and (0xFF shr len)).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
            pos += len
            return v
        }
        if (vint(true) != 0x1A45DFA3L) return null      // EBML header
        val headSize = vint(false)
        if (headSize < 0 || headSize > b.size) return null
        pos += headSize.toInt()
        if (vint(true) != 0x18538067L) return null      // Segment
        val segSize = vint(false)
        if (segSize < 0) return null
        val segEnd = if (segSize > b.size.toLong()) b.size.toLong() else pos + segSize
        var scale = 1_000_000L                          // TimestampScale default: 1ms
        var dur: Double? = null
        while (pos < segEnd && pos < b.size) {
            val id = vint(true); if (id < 0) break
            val sz = vint(false); if (sz < 0) break
            val end = if (sz > b.size.toLong()) b.size.toLong() else pos + sz
            if (id == 0x1549A966L) {                    // Info
                val infoEnd = minOf(end, b.size.toLong()).toInt()
                while (pos < infoEnd) {
                    val iid = vint(true); if (iid < 0) break
                    val isz = vint(false); if (isz < 0) break
                    val iEnd = if (isz > b.size.toLong()) b.size.toLong() else pos + isz
                    when (iid) {
                        0x2AD7B1L -> {                  // TimestampScale (nanoseconds)
                            var v = 0L
                            val lim = minOf(iEnd, b.size.toLong()).toInt()
                            for (k in pos until lim) v = (v shl 8) or (b[k].toLong() and 0xFF)
                            if (v > 0) scale = v
                        }
                        0x4489L -> {                    // Duration (float, in TimestampScale units)
                            dur = when {
                                isz == 4L && pos + 4 <= b.size ->
                                    java.lang.Float.intBitsToFloat(be32(b, pos).toInt()).toDouble()
                                isz == 8L && pos + 8 <= b.size ->
                                    java.lang.Double.longBitsToDouble(be64(b, pos))
                                else -> null
                            }
                        }
                    }
                    pos = iEnd.toInt()
                }
                if (dur != null) break
            }
            pos = end.toInt()
        }
        val d = dur ?: return null
        if (d <= 0 || d.isNaN() || d.isInfinite()) return null
        return d * scale / 1_000_000_000.0
    }

    /** MP3 — Xing/Info VBR frame count when present, else a CBR estimate from the total size. */
    private fun mp3(head: ByteArray, totalSize: Long): Double {
        val BR_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val BR_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        val RATES = intArrayOf(44100, 48000, 32000)
        var o = 0
        // skip an IDv2 tag
        if (head.size >= 10 && head[0] == 0x49.toByte() && head[1] == 0x44.toByte() && head[2] == 0x33.toByte()) {
            val sz = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            o = 10 + sz
        }
        while (o + 4 <= head.size) {
            if ((head[o].toInt() and 0xFF) != 0xFF || (head[o + 1].toInt() and 0xE0) != 0xE0) { o++; continue }
            val b2 = head[o + 1].toInt() and 0xFF
            val verBits = (b2 shr 3) and 0x03           // 11=MPEG1 · 10=MPEG2 · 00=MPEG2.5
            val layBits = (b2 shr 1) and 0x03           // 01=Layer III
            if (verBits == 1 || layBits != 1) { o++; continue }
            val b3 = head[o + 2].toInt() and 0xFF
            val brIdx = (b3 shr 4) and 0x0F
            val srIdx = (b3 shr 2) and 0x03
            if (brIdx == 0 || brIdx == 15 || srIdx == 3) { o++; continue }
            val mpeg1 = verBits == 3
            val bitrate = (if (mpeg1) BR_V1[brIdx] else BR_V2[brIdx]) * 1000
            val rate = RATES[srIdx] / (if (mpeg1) 1 else 2)
            val mono = ((b3 shr 6) and 0x03) == 3
            // the Xing/Info VBR header sits right after the frame's side information
            val x = o + 4 + (if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17))
            if (x + 12 <= head.size) {
                val tag = String(head, x, 4, Charsets.US_ASCII)
                if (tag == "Xing" || tag == "Info") {
                    val flags = be32(head, x + 4)
                    if (flags and 0x1L != 0L) {
                        val frames = be32(head, x + 8)
                        val spf = if (mpeg1) 1152 else 576
                        if (frames > 0) return frames * spf.toDouble() / rate
                    }
                }
            }
            // CBR estimate — audio bytes × 8 ÷ bitrate
            if (totalSize > o && bitrate > 0) return (totalSize - o) * 8.0 / bitrate
            return -1.0
        }
        return -1.0
    }
}
