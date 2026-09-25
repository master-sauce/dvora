package com.dvora.dvora20.adblock

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * The browser's page client: keeps http(s) navigation inside the app, hands
 * every other scheme (mailto:, tel:, market:, stremio:, intent:, …) to an
 * external app, and blocks requests matched by the compiled filter engine —
 * answered with a 204 / empty body so the page sees nothing at all.
 *
 * All callbacks run on the UI thread (WebView guarantees this) so they can
 * update Compose state directly.
 */
class BlockerWebViewClient(
    private val appCtx: Context,
    private val repo: ListRepo,
    /** current per-site whitelist (registrable hosts) — supplier so the sheet can toggle it live. */
    private val allowedHosts: () -> Set<String>
) : WebViewClient() {

    // ── UI callbacks ──────────────────────────────────────────────────────────
    var onUrl: (String) -> Unit = {}
    var onPageStart: (String, String) -> Unit = { _, _ -> }    // (page doc host, url), after counters were reset
    var onPageEnd: () -> Unit = {}
    var onBlocked: () -> Unit = {}              // page-block counter just moved → refresh badge
    var onActivity: () -> Unit = {}             // another page request happened → live the resources panel
    var onCrossNav: ((String, String) -> Unit)? = null   // (target url, host) — confirm-site-change dialog
    var onSsl: ((Uri?, SslErrorHandler) -> Unit)? = null

    @Volatile
    var docHost: String = ""                     // current page's host (set from outside on approved cross-nav)

    // ── navigation ────────────────────────────────────────────────────────────────

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme !in setOf("http", "https")) {
            // mailto: / tel: / sms: / geo: / market: / stremio: / intent: … → appropriate external app
            try {
                appCtx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                // no installed handler — ignore
            }
            return true
        }
        onUrl(uri.toString())
        val newHost = (uri.host ?: "").lowercase()
        // an actual site change (different registrable domain) while browsing → ask the user
        if (docHost.isNotEmpty() && newHost.isNotEmpty() &&
            BlockerEngine.registrable(newHost) != BlockerEngine.registrable(docHost)
        ) {
            onCrossNav?.invoke(uri.toString(), (uri.host ?: ""))
            return true                            // cancelled — the UI loads it again on approve
        }
        return false                               // same site → stays inside the app's WebView
    }

    // ── filtering ─────────────────────────────────────────────────────────────

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
        if (!repo.isMaster()) return null
        val uri = request.url
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isEmpty()) return null
        for (a in allowedHosts()) if (host == a || host.endsWith(".$a")) return null    // per-site whitelist

        val engine = repo.engine
        if (engine.totalRules + engine.totalException == 0) return null                  // lists still loading

        val kind = kindOf(request)
        val tag = tagOf(request, kind)
        val url = uri.toString()
        val rule = engine.match(url, host, kind, docHost)        // null = allow / exception wins
        when {
            rule != null -> {
                engine.noteReq(tag, url, true, "${rule.pattern()} — ${rule.list}")
                onBlocked()
                return empty(kind)
            }

            else -> engine.noteReq(tag, url, false, "")
        }
        onActivity()
        return null
    }

    private fun kindOf(req: WebResourceRequest): ResourceKind {
        if (req.isForMainFrame) return ResourceKind.DOCUMENT
        val path = (req.url.path ?: "").lowercase()
        val accept = (req.requestHeaders["Accept"] ?: "").lowercase()
        val method = req.method.uppercase()
        if (method in setOf("POST", "PUT", "PATCH", "DELETE")) return ResourceKind.XHR
        val ext = path.substringAfterLast('.')
        return when {
            accept.contains("text/css") || path.endsWith(".css") -> ResourceKind.STYLESHEET
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "avif", "bmp", "ico", "cur") -> ResourceKind.IMAGE
            path.endsWith(".js") || accept.contains("javascript") -> ResourceKind.SCRIPT
            accept.contains("application/xml") || path.endsWith(".xml") || path.endsWith(".json") -> ResourceKind.XHR
            accept.contains("text/html") -> ResourceKind.SUBDOCUMENT
            else -> ResourceKind.OTHER
        }
    }

    /** display tag for the resources panel: page / js / css / img / frame / api / media / req */
    private fun tagOf(req: WebResourceRequest, kind: ResourceKind): String = when (kind) {
        ResourceKind.DOCUMENT -> "page"
        ResourceKind.SUBDOCUMENT -> "frame"
        ResourceKind.SCRIPT -> "js"
        ResourceKind.STYLESHEET -> "css"
        ResourceKind.IMAGE -> "img"
        ResourceKind.XHR -> "api"
        else -> {
            val ext = (req.url.path ?: "").lowercase().substringAfterLast('.')
            val accept = (req.requestHeaders["Accept"] ?: "").lowercase()
            if (ext in MEDIA_EXT || accept.contains("video/") || accept.contains("audio/") ||
                accept.contains("mpegurl")
            ) "media" else "req"
        }
    }

    private fun empty(kind: ResourceKind): WebResourceResponse {
        val mime = when (kind) {
            ResourceKind.IMAGE -> "image/png"
            ResourceKind.SCRIPT -> "application/javascript"
            ResourceKind.STYLESHEET -> "text/css"
            ResourceKind.DOCUMENT, ResourceKind.SUBDOCUMENT -> "text/html"
            ResourceKind.XHR -> "application/json"
            else -> "text/plain"
        }
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-store"
        headers["Access-Control-Allow-Origin"] = "*"
        return WebResourceResponse(mime, "UTF-8", 204, "Blocked by Dvora", headers, ByteArrayInputStream(ByteArray(0)))
    }

    private companion object {
        val MEDIA_EXT = setOf(
            "mp4", "webm", "m3u8", "m4s", "m4a", "m4v", "ogv", "ogg", "mp3", "wav",
            "flv", "avi", "mkv", "mov", "aac", "vtt", "srt", "ass", "ts"
        )
    }

    // ── page lifecycle ────────────────────────────────────────────────────────

    override fun onPageStarted(view: WebView?, url: String?, faviconBitmap: Bitmap?) {
        if (url.isNullOrBlank()) return
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return
        }
        if ((uri.scheme ?: "").lowercase() !in setOf("http", "https")) return
        docHost = (uri.host ?: "").lowercase()
        repo.engine.newPage()          // fresh per-page counters / logs
        onPageStart(docHost, url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        repo.flushLifetime()           // keep the persisted counter current
        onPageEnd()
    }

    // ── SSL — user dialog, never silently proceed ─────────────────────────────

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, sslError: SslError?) {
        val h = handler ?: return
        // on API 36 SslError.getUrl() is a String again — parse it for the dialog state
        val uri = sslError?.url?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        val cb = onSsl
        if (cb != null) cb(uri, h) else h.cancel()
    }
}

/**
 * The browser's chrome client — page title / favicon / load-progress callbacks
 * (delivered here on API 36) and granting file access for `<input type="file">`
 * uploads via the system document picker.
 */
class BlockerChromeClient : WebChromeClient() {

    var onTitle: (String) -> Unit = {}
    var onProgress: (Int) -> Unit = {}
    var onIcon: (Bitmap?) -> Unit = {}
    var onFileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit)? = null

    override fun onReceivedTitle(view: WebView?, title: String?) {
        onTitle(title ?: "")
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        onIcon(icon)
    }

    override fun onShowFileChooser(
        view: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileInputParams: WebChromeClient.FileChooserParams
    ): Boolean {
        val cb = onFileChooser ?: run {
            filePathCallback.onReceiveValue(null)
            return true
        }
        cb(filePathCallback, fileInputParams)
        return true
    }
}
