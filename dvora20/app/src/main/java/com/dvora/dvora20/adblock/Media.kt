package com.dvora.dvora20.adblock

/**
 * Media URL sniffer — Kotlin port of the capture logic from the user's
 * other app (background.js + content.js): playlist / manifest /
 * direct-video URL classes, the loose network-interceptor sniff, and the
 * Content-Type header fallback. `sniff` decides which page requests are
 * downloadable media; `normalize` is the query-stripped dedup key so the
 * same playlist isn't re-listed on every segment request.
 */
object Media {

    /** /(playlist|manifest|index|master|video|hls|stream)(.php|.aspx|.m3u8)?(?|$|/) — endpoint shape */
    private val PATH_RE =
        Regex(
            "/(?:playlist|manifest|index|master|video|hls|stream)(?:\\.php|\\.aspx|\\.m3u8)?(?:\\?|$|/)",
            RegexOption.IGNORE_CASE
        )

    /** loose ext sniff: playable + segment containers */
    private val VIDEO_EXTS =
        Regex("\\.(?:m3u8|mpd|mp4|webm|mkv|ts|m4s|m4v|mov|avi|flv)(?:$|\\?|#)", RegexOption.IGNORE_CASE)

    private val MPD_ENDPOINT =
        Regex("/(?:manifest|mpd)(?:\\.php|\\.aspx|\\.xml)?(?:\\?|$)", RegexOption.IGNORE_CASE)

    private val DIRECT_EXT =
        Regex("\\.(?:mp4|webm|mkv|avi|mov|flv|wmv|ogv|m4v)(?:$|\\?)", RegexOption.IGNORE_CASE)

    /** first-match extension capture — used for the row label */
    private val EXT_CAPTURE =
        Regex("\\.(?:mp4|webm|mkv|avi|mov|flv|wmv|ogv|m4v)(?:$|\\?|#)")

    fun looksLikePlaylist(url: String): Boolean {
        val u = url.lowercase()
        return "playlist" in u || "manifest" in u ||
                "/hls/" in u || "/dash/" in u ||
                "/stream" in u || "/video" in u || "/media" in u ||
                "index.m3u" in u || "master.m3u" in u
    }

    fun isM3U8(url: String): Boolean {
        val u = url.lowercase()
        return "m3u8" in u || (PATH_RE.containsMatchIn(u) && looksLikePlaylist(u))
    }

    fun isMPD(url: String): Boolean {
        val u = url.lowercase()
        return ".mpd" in u || "/dash/" in u || "dash.xml" in u || MPD_ENDPOINT.containsMatchIn(u)
    }

    fun isDirectVideo(url: String): Boolean = DIRECT_EXT.containsMatchIn(url)

    /** second, looser sniff — ext or playlist-shaped path */
    fun looksLikeVideo(url: String): Boolean {
        val u = url.lowercase()
        return VIDEO_EXTS.containsMatchIn(u) || PATH_RE.containsMatchIn(u)
    }

    /**
     * Classify [url] (+ optional Content-Type [ct]) into its detected kind —
     * "m3u8" / "mpd" / container extension — or null when not media.
     *
     * URL classes first, then the header fallback, so extension-less
     * endpoints still land when their answer advertises a playlist/manifest.
     */
    fun sniff(url: String, ct: String = ""): String? {
        if (isM3U8(url)) return "m3u8"
        if (isMPD(url)) return "mpd"
        val ext = EXT_CAPTURE.find(url.lowercase())?.value?.drop(1)?.uppercase()
        if (ext != null) return ext
        if (looksLikeVideo(url)) return "video"
        val c = ct.lowercase()
        if ("mpegurl" in c || "application/hls" in c || "x-hls" in c) return "m3u8"
        if ("dash+xml" in c || "mpd" in c) return "mpd"
        if ("octet-stream" in c || "text/plain" in c) {
            if (looksLikePlaylist(url)) return "m3u8"
        }
        return null
    }

    /** dedup key — query string stripped, lowercased, like the original JS. */
    fun normalize(url: String): String = url.substringBefore('?').substringBefore('#').lowercase()
}
