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
    var onPageStart: (String) -> Unit = {}      // page doc host, after counters were reset
    var onPageEnd: () -> Unit = {}
    var onBlocked: () -> Unit = {}              // page-block counter just moved → refresh badge
    var onSsl: ((Uri?, SslErrorHandler) -> Unit)? = null

    @Volatile
    private var docHost: String = ""

    // ── navigation ────────────────────────────────────────────────────────────

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        val uri = request.url
        onUrl(uri.toString())
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "http" || scheme == "https") return false    // stays inside the app's WebView
        // mailto: / tel: / sms: / geo: / market: / stremio: / intent: … → appropriate external app
        try {
            appCtx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // no installed handler — ignore
        }
        return true
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
        val rule =
            engine.match(uri.toString(), host, kind, docHost) ?: return null      // null = allow / exception wins
        onBlocked()
        return empty(kind)
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
        repo.engine.newPage()          // fresh per-page block counters / log
        onPageStart(docHost)
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
