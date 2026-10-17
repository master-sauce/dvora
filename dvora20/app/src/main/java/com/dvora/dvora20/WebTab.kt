package com.dvora.dvora20

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dvora.dvora20.adblock.BlockerChromeClient
import com.dvora.dvora20.adblock.BlockerEngine
import com.dvora.dvora20.adblock.BlockerWebViewClient
import com.dvora.dvora20.adblock.CapturedMedia
import com.dvora.dvora20.adblock.ListRepo
import com.dvora.dvora20.adblock.SniffName
import com.dvora.dvora20.adblock.SnifferJsInterface
import com.dvora.dvora20.adblock.SnifferStore

/**
 * One open browser tab: its OWN WebView + page client + chrome client and all
 * of its per-page UI state (address / title / favicon / progress / blocked
 * badge / ssl dialog / fullscreen video). Every state field is Compose state,
 * so callbacks from a BACKGROUND tab write silently and only the active tab's
 * fields are read by the UI.
 *
 * Incognito tabs turn off every persistent store the WebView can touch —
 * cookies (app-wide accept is toggled off while any incognito tab lives),
 * DOM storage, database, form data, cache — and die with their WebView.
 */
class WebTab(
    context: Context,
    private val repo: ListRepo,
    startUrl: String,
    val incognito: Boolean,
    allowedHosts: () -> Set<String>,
    /** cross-site navigation needs the user's ok — (tab, url, host) */
    onCrossNav: (WebTab, String, String) -> Unit,
    /** the request log moved — live the resources panel */
    onActivity: () -> Unit,
    /** `<input type="file">` picker is a screen-level launcher */
    onPick: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    /** a forced download (Content-Disposition) goes to the DownloadManager */
    onEnqueue: (String, String?, String?) -> Unit
) {
    // ── per-page UI state (own state per tab — background tabs update silently) ──
    var address by mutableStateOf(startUrl)
    var pageTitle by mutableStateOf("")
    var favicon by mutableStateOf<Bitmap?>(null)
    var progress by mutableIntStateOf(0)
    var pageHost by mutableStateOf("")
    var blocked by mutableIntStateOf(0)
    var blockLog by mutableStateOf<List<BlockerEngine.BlockRecord>>(emptyList())
    var ssl by mutableStateOf<Pair<Uri?, SslErrorHandler>?>(null)
    var video by mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null)   // fullscreen video overlay
    var navTick by mutableIntStateOf(0)    // back/fwd availability moved → refresh the toolbar

    // ── one-time objects ──────────────────────────────────────────────────────
    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = !incognito      // incognito: nothing persists to disk
        settings.databaseEnabled = !incognito
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = if (incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setGeolocationEnabled(false)
        setBackgroundColor(0xFF1C1500.toInt())
    }
    val client = BlockerWebViewClient(context, repo, allowedHosts).also { c ->
        c.injectSniffer = { wv -> runCatching { wv.evaluateJavascript(SnifferJsInterface.SCRIPT, null) } }
    }
    val chrome = BlockerChromeClient()
    private val jsInterface = SnifferJsInterface(context) { webView.settings.userAgentString }

    init {
        webView.addJavascriptInterface(jsInterface, "JSInterface")     // 1DM's JSInterface bridge
        webView.webViewClient = client
        webView.webChromeClient = chrome
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !incognito)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
            // forced downloads join the capture list (1DM marks these so they never double-list)
            if (url.startsWith("http")) {
                val mime = mimeType ?: ""
                SnifferStore.add(
                    CapturedMedia(
                        url = url,
                        kind = SniffName.resolve(url, mime, contentDisposition)
                            ?.substringAfterLast('.')?.lowercase()?.takeIf { it.isNotEmpty() }
                            ?: (mime.substringAfter('/').takeIf { it.isNotEmpty() && it != "*" } ?: "file"),
                        mime = mime,
                        size = contentLength,
                        filename = SniffName.resolve(url, mime, contentDisposition),
                        referer = address.takeIf { it.startsWith("http") },
                        cookie = null
                    )
                )
            }
            onEnqueue(url, contentDisposition, mimeType)
        }

        chrome.onFileChooser = { cb, params -> onPick(cb, params) }
        chrome.onVideo = { v, cb -> video = v to cb }
        chrome.onVideoExited = { video = null }
        chrome.onTitle = { if (it.isNotBlank()) pageTitle = it }
        chrome.onProgress = { progress = it; navTick++ }
        chrome.onIcon = { favicon = it }
        client.onUrl = { address = it; navTick++ }
        // the real-time address fix: every committed url incl. SPA pushState lands here
        client.onVisited = { url -> if (url != address) { address = url; navTick++ } }
        client.onPageStart = { host, url ->
            video?.let { it.second.onCustomViewHidden() }   // page changed → leave fullscreen video
            video = null
            pageHost = host
            pageTitle = ""
            favicon = null
            blocked = 0
            blockLog = emptyList()
            SnifferStore.newPage()                       // 1DM keeps one capture list per page
            address = url                                // bar always tracks the page actually loaded (redirects / history)
            navTick++
        }
        client.onActivity = onActivity
        client.onCrossNav = { url, host -> onCrossNav(this, url, host) }
        client.onPageEnd = {
            blockLog = repo.engine.blockSnapshot()
            blocked = repo.engine.pageBlocks
            // 1DM scans every body it can reach — the page's own DOM too: <video>/<source>
            // elements and iframes that never issue an XHR still carry captureable media
            runCatching {
                webView.evaluateJavascript(
                    "(function(){try{var h=document.documentElement.outerHTML;" +
                        "window.JSInterface.scanPage(h.length>2000000?h.substring(0,2000000):h,window.location.href)}catch(e){}})()",
                    null
                )
            }
        }
        client.onBlocked = { blocked = repo.engine.pageBlocks }
        client.onSsl = { uri, handler -> ssl = uri to handler }

        webView.loadUrl(startUrl)
    }

    /** address snaps to the target so the row reflects what is loading even before commit. */
    fun load(url: String) {
        address = url
        webView.loadUrl(url)
    }

    /** back/fwd availability — reading navTick makes Compose refresh these on every history change. */
    fun canBack(): Boolean = navTick.let { webView.canGoBack() }
    fun canFwd(): Boolean = navTick.let { webView.canGoForward() }

    /** tears the tab down — WebView.destroy() frees its renderer slot; safe to call twice. */
    fun destroy() {
        runCatching { video?.let { it.second.onCustomViewHidden() } }
        video = null
        runCatching { webView.stopLoading() }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
    }
}
