package com.dvora.dvora20.adblock

import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * The 1DM-style media sniffer core.
 *
 * Three pieces:
 *  - [SnifferStore]  — the per-page capture list (deduped, cleared on every page start)
 *  - [SniffName]     — filename resolution: Content-Disposition filename* (charset+percent
 *                      decoding) → url filename/title query params → path segment →
 *                      mime-derived extension with wrong-extension correction
 *  - [SnifferJsInterface] — the `JSInterface` bridge installed on the WebView; the injected
 *                      script (1DM's XMLHttpRequest/fetch overrides) calls into it on every
 *                      ajax response so classification sees the REAL response headers and
 *                      body, not just the request url.
 */
data class CapturedMedia(
    val url: String,
    val kind: String,        // m3u8 / mpd / mp4 / webm / audio / pdf / zip …
    val mime: String,
    val size: Long,          // bytes, -1 unknown
    val filename: String?,   // resolved name, null when only a guess would be possible
    val referer: String?,    // exact referer the page used for this resource
    val cookie: String?      // exact Cookie header the page used, when the page set one
)

/** row model for the media picker / badge */
data class SniffRow(
    val url: String,
    val label: String,
    val size: Long,
    val name: String?,
    val isSegment: Boolean,
    val isPlaylist: Boolean
)

object SnifferStore {

    private val lock = Any()
    private val captures = LinkedHashSet<CapturedMedia>()   // first-seen order
    private val seen = HashSet<String>()                    // lowercase url (fragment stripped)

    /** page navigation — drop every capture of the old page */
    fun newPage() {
        synchronized(lock) { captures.clear(); seen.clear() }
    }

    /** returns false when the url was already captured on this page */
    fun add(c: CapturedMedia): Boolean = synchronized(lock) {
        val key = c.url.lowercase().substringBefore('#')
        if (c.url.isBlank() || key in seen) return false
        seen.add(key)
        captures.add(c)
        true
    }

    fun all(): List<CapturedMedia> = synchronized(lock) { captures.toList() }

    /** playlists first — the entry point yt-dlp needs for a stream */
    fun playlists(): List<CapturedMedia> = all().filter { it.kind == "m3u8" || it.kind == "mpd" }

    fun byUrl(url: String): CapturedMedia? =
        all().firstOrNull { it.url.equals(url, ignoreCase = true) }
}

// ═══════════════════════════════════════════════════════════════════════════════
// URL / MIME classification (1DM extension sets, trimmed to what browsers hit)
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
            if (size >= 0 && size < 10_240) return null               // 1DM's ≥10KB gate kills beacons/icons
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
}

// ═══════════════════════════════════════════════════════════════════════════════
// Filename resolution (1DM's algorithm, condensed)
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
                // query params of the url (1DM parses filename / title / mime)
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

class SnifferJsInterface {

    /** every ajax/fetch call — log-only; subresource requests already reach shouldInterceptRequest */
    @JavascriptInterface
    fun logXHR(pageUrl: String, url: String, method: String) {
        // no-op: kept as the bridge surface for the injected script's open() wrapper
    }

    /**
     * The classifier hook — fired on every ajax/fetch response with its real headers + body.
     * Ported from 1DM's `JSInterface.handleM3u8Url`.
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
            if (kind == null && b.length in 7..250_000) {
                val trimmed = b.trimStart()
                when {
                    trimmed.length >= 7 && trimmed.substring(0, 7).equals("#EXTM3U", true) ->
                        kind = "m3u8"
                    trimmed.startsWith("<") || trimmed.startsWith("{") ->
                        // html page fragments and json player configs both carry direct media urls
                        extractFromHtml(trimmed, resolved, pageUrl)
                }
            }
            if (kind == null) return
            if (kind !in setOf("m3u8", "mpd") && len in 1..10_239) return   // size gate

            var cookie: String? = null
            var referer: String? = null
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
            }

            val cap = CapturedMedia(
                url = resolved,
                kind = kind,
                mime = mime,
                size = len,
                filename = SniffName.resolve(resolved, mime, contentDisposition),
                referer = referer,
                cookie = cookie
            )
            if (SnifferStore.add(cap)) {
                // playlists get their children captured too (master → media/audio/subtitle tracks)
                if (kind == "m3u8" && b.isNotEmpty()) extractM3u8Children(b, resolved, pageUrl)
            }
        } catch (_: Throwable) {
        }
    }

    /** m3u8 body → child playlists + per-attribute URI="…" tracks (1DM's Extract, regex-lite) */
    private fun extractM3u8Children(body: String, baseUrl: String, pageUrl: String) {
        try {
            for (line in body.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val child = Sniff.resolveUrl(baseUrl, t)
                val k = Sniff.kind(child, "", null, -1)
                if (k != null && (k == "m3u8" || k == "mpd" || k in setOf("aac", "mp3", "vtt", "srt"))) {
                    SnifferStore.add(CapturedMedia(child, k, "", -1, SniffName.resolve(child, "", null), pageUrl, null))
                }
            }
            // attribute URIs: #EXT-X-MEDIA / #EXT-X-STREAM-INF … URI="..."
            Regex("""URI="([^"]+)""").findAll(body).forEach { m ->
                val child = Sniff.resolveUrl(baseUrl, m.groupValues[1])
                val k = Sniff.kind(child, "", null, -1)
                if (k != null && k != "ts") {
                    SnifferStore.add(CapturedMedia(child, k, "", -1, SniffName.resolve(child, "", null), pageUrl, null))
                }
            }
        } catch (_: Exception) {
        }
    }

    /** html/json body scan — absolute media urls only (conservative; 1DM uses Jsoup on the same idea) */
    private fun extractFromHtml(body: String, baseUrl: String, pageUrl: String) {
        try {
            Regex(
                """https?://[^"'\\\s<>]+?\.(?:m3u8|mpd|mp4|webm|mkv|avi|mov|m4v|flv|mp3|m4a)(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(body).forEach { m ->
                val child = m.value
                val k = Sniff.kind(child, "", null, -1) ?: return@forEach
                if (k == "ts") return@forEach
                SnifferStore.add(CapturedMedia(child, k, "", -1, SniffName.resolve(child, "", null), pageUrl, null))
            }
        } catch (_: Exception) {
        }
    }

    companion object {

        /**
         * Injected on every page start (idempotent — the `realOpen`/`realFetch` guards make
         * re-injection a no-op). Ported from 1DM's injectAjaxOverrideScript: wraps
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
                this.addEventListener('load', function() {
                  try {
                    var h = this.getAllResponseHeaders().toLowerCase();
                    window.JSInterface.handleM3u8Url(
                      method, url, this.responseURL, this.responseText,
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
              };
              window.realFetch = window.fetch;
              function report(response, resource, method, init, data) {
                try {
                  var rUrl = typeof resource === 'string' ? resource : (resource && resource.url ? resource.url : String(resource));
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
                try { window.JSInterface.logXHR(window.location.href, typeof resource === 'string' ? resource : (resource && resource.url ? resource.url : String(resource)), method); } catch (e4) {}
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
              };
            })();
        """.trimIndent()
    }
}
