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
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One open browser tab: its OWN WebView + page client + chrome client and all
 * of its per-page UI state (address / title / favicon / progress / blocked
 * badge / ssl dialog / fullscreen video). Every state field is Compose state,
 * so callbacks from a BACKGROUND tab write silently and only the active tab's
 * fields are read by the UI.
 *
 * Incognito tabs turn off every persistent store the WebView can touch —
 * cookies (app-wide accept is toggled off while any incognito tab lives),
 * DOM storage, database, form data, cache — die with the browser screen,
 * and never report their pages to HistoryStore.
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
        webView.addJavascriptInterface(jsInterface, "JSInterface")     // the engine's JSInterface bridge
        webView.webViewClient = client
        webView.webChromeClient = chrome
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !incognito)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
            // forced downloads join the capture list (the download manager marks these so they never double-list)
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
        chrome.onTitle = {
            if (it.isNotBlank()) {
                pageTitle = it
                if (!incognito) HistoryStore.setTitle(address, it)   // incognito pages never record
            }
        }
        chrome.onProgress = { progress = it; navTick++ }
        chrome.onIcon = { favicon = it }
        client.onUrl = { address = it; navTick++ }
        // the real-time address fix: every committed url incl. SPA pushState lands here
        client.onVisited = { url ->
            if (!incognito) HistoryStore.add(url)   // history keeps every committed page — incognito never records
            ytWatchUrl(url)?.let { probeYouTube(it) }   // SPA route change on youtube → the new video
            if (url != address) { address = url; navTick++ }
        }
        client.onPageStart = { host, url ->
            video?.let { it.second.onCustomViewHidden() }   // page changed → leave fullscreen video
            video = null
            pageHost = host
            pageTitle = ""
            favicon = null
            blocked = 0
            blockLog = emptyList()
            SnifferStore.newPage()                       // the download manager keeps one capture list per page
            ytWatchUrl(url)?.let { probeYouTube(it) }    // a YouTube page loads → its download row
            address = url                                // bar always tracks the page actually loaded (redirects / history)
            navTick++
        }
        client.onActivity = onActivity
        client.onCrossNav = { url, host -> onCrossNav(this, url, host) }
        client.onPageEnd = {
            blockLog = repo.engine.blockSnapshot()
            blocked = repo.engine.pageBlocks
            // the download manager scans every body it can reach — the page's own DOM too: <video>/<source>
            // elements and iframes that never issue an XHR still carry captureable media
            runCatching {
                webView.evaluateJavascript(
                    "(function(){try{var h=document.documentElement.outerHTML;" +
                        "window.JSInterface.scanPage(h.length>2000000?h.substring(0,2000000):h,window.location.href)}catch(e){}})()",
                    null
                )
            }
            // heal: a settled YouTube page always carries its row — the first probe may
            // have raced a store wipe or died against a busy (self-updating) yt-dlp binary
            ytWatchUrl(address)?.let { w -> if (SnifferStore.byUrl(w) == null) probeYouTube(w, force = true) }
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

    // ── YouTube — yt-dlp knows the video itself, one probe plants the download row ──

    private val ytScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ytTried = mutableMapOf<String, Long>()   // url → last probe attempt (millis)

    /** a canonical YouTube watch url from any shape (watch?v= / shorts / youtu.be / embed), else null. */
    fun ytWatchUrl(u: String): String? {
        val uri = Uri.parse(u)
        val host = (uri.host ?: "").lowercase()
        if (host != "youtu.be" && !host.endsWith("youtube.com")) return null
        val v = uri.getQueryParameter("v")
        return when {
            !v.isNullOrBlank() -> "https://www.youtube.com/watch?v=$v"
            host == "youtu.be" -> "https://www.youtube.com/watch?v=" + (uri.path ?: "").trim('/')
            uri.path?.startsWith("/shorts/") == true || uri.path?.startsWith("/embed/") == true ->
                "https://www.youtube.com/watch?v=" + (uri.path ?: "").substringAfterLast('/')
            else -> null
        }
    }

    /**
     * A YouTube page → one yt-dlp probe resolves the real title / duration / quality
     * ladder and plants a YOUTUBE row in the media picker. Tapping the row hands THIS
     * SAME url to the download service — yt-dlp downloads the page itself, because
     * the player's googlevideo links are signed and expire while the page url never is.
     *
     * [force] is the heal path — the page IS youtube but its row is missing (the
     * store was wiped by another tab's navigation, or the probe failed e.g. while
     * the yt-dlp binary was busy self-updating at app start) → retried at most
     * every 5s, so nothing races or fails permanently.
     */
    fun probeYouTube(watchUrl: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = ytTried[watchUrl] ?: 0L
        if (last > 0 && (!force || now - last < 5_000)) return
        ytTried[watchUrl] = now
        ytScope.launch {
            try {
                val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(watchUrl))
                val qualities = info.formats.orEmpty()
                    .mapNotNull { it.height }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()
                    .joinToString("·") { "${it}p" }
                    .takeIf { it.isNotEmpty() }
                SnifferStore.add(
                    CapturedMedia(
                        url = watchUrl,
                        kind = "youtube",
                        mime = "video/mp4",
                        size = -1,
                        filename = info.title?.takeIf { it.isNotBlank() },
                        referer = watchUrl,
                        cookie = null,
                        duration = info.duration?.toDouble() ?: -1.0,
                        qualities = qualities
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    /** tears the tab down — WebView.destroy() frees its renderer slot; safe to call twice. */
    fun destroy() {
        runCatching { video?.let { it.second.onCustomViewHidden() } }
        video = null
        runCatching { webView.stopLoading() }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
    }
}
