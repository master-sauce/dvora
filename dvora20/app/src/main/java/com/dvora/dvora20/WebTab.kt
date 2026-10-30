package com.dvora.dvora20

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
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

/** the document a home-sentinel tab rests on while its Compose start page is shown. */
private const val BLANK_PAGE = "about:blank"

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
    /** app theme at creation — the page's prefers-color-scheme + algorithmic darkening follow it */
    private val darkMode: Boolean,
    allowedHosts: () -> Set<String>,
    /** cross-site navigation needs the user's ok — (tab, url, host) */
    onCrossNav: (WebTab, String, String) -> Unit,
    /** the request log moved — live the resources panel */
    onActivity: () -> Unit,
    /** `<input type="file">` picker is a screen-level launcher */
    onPick: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    /** a forced download (Content-Disposition) goes to the DownloadManager */
    onEnqueue: (String, String?, String?) -> Unit,
    /** long-press on an image / link — (url, isImage, linkText); the screen shows the action menu */
    private val onLongPress: (String, Boolean, String?) -> Unit
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
    // the WebView is built on the ACTIVITY context so it inherits the app's real
    // window theme — algorithmic darkening is refused by Chromium when the host
    // theme is light, and a createConfigurationContext wrapper loses that theme
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
    }.also { wv ->
        // algorithmic darkening as the single darkener (the legacy FORCE_DARK gate
        // maps onto this API on modern WebViews)
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, darkMode)
            } else if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
                androidx.webkit.WebSettingsCompat.setForceDark(
                    wv.settings,
                    if (darkMode) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON
                    else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF
                )
            }
        }
    }
    val client = BlockerWebViewClient(context, repo, allowedHosts).also { c ->
        c.injectSniffer = { wv -> runCatching { wv.evaluateJavascript(SnifferJsInterface.SCRIPT, null) } }
    }
    val chrome = BlockerChromeClient()
    private val jsInterface = SnifferJsInterface(context) { webView.settings.userAgentString }

    /** the first navigation — held until the WebView is attached (see init) */
    private var pendingUrl: String? = null

    /** what the finger was over when the long press fired — the node query's fallback */
    private var pressHitUrl: String? = null
    private var pressHitImage = false

    /** the last menu handed to the screen — the two long-press paths answer twice */
    private var lastOfferAt = 0L
    private var lastOfferUrl = ""

    /**
     * Long-press answer: the page tells us the focused node — link href, <img> src
     * and the anchor text — which is far more reliable than the touch point alone.
     */
    private val longPressHandler = Handler(Looper.getMainLooper()) { msg ->
        val data = msg.data
        val href = data.getString("url")?.takeIf { it.startsWith("http") }
        val src = data.getString("src")?.takeIf { it.startsWith("http") }
        val text = data.getString("title")?.takeIf { it.isNotBlank() }
        // the page has no focused node (or the message came back empty) → the
        // element the finger was over when the long press fired
        val target = src ?: href ?: pressHitUrl
        val image = src != null ||
            (target != null && target == pressHitUrl && pressHitImage)
        if (target != null) offerLongPress(target, image, text)
        true
    }

    /** the gesture path and the node query both answer — one menu, richer data wins. */
    private fun offerLongPress(u: String, image: Boolean, text: String?) {
        val now = SystemClock.uptimeMillis()
        val duplicate = now - lastOfferAt < 1200 && u == lastOfferUrl
        if (duplicate && text == null) return      // same target, nothing new to add
        lastOfferAt = now
        lastOfferUrl = u
        // shown as soon as the press resolves — no need to lift the finger
        webView.postDelayed({ onLongPress(u, image, text) }, 40)
    }

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
            // a blank document reports its own url as the title ("about:blank") and the
            // start page would inherit it — home tabs keep their own name in the tab list
            if (it.isNotBlank() && it != BLANK_PAGE && address != BROWSER_HOME) {
                pageTitle = it
                if (!incognito) HistoryStore.setTitle(address, it)   // incognito pages never record
            }
        }
        chrome.onProgress = { progress = it; navTick++ }
        chrome.onIcon = { favicon = it }
        client.onUrl = { address = pageAddress(it); navTick++ }
        // the real-time address fix: every committed url incl. SPA pushState lands here
        client.onVisited = { url ->
            if (!incognito) HistoryStore.add(url)   // history keeps every committed page — incognito never records
            ytWatchUrl(url)?.let { probeYouTube(it) }   // SPA route change on youtube → the new video
            val shown = pageAddress(url)
            if (shown != address) { address = shown; navTick++ }
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
            address = pageAddress(url)                   // bar always tracks the page actually loaded (redirects / history)
            navTick++
        }
        client.onActivity = onActivity
        client.onCrossNav = { url, host -> onCrossNav(this, url, host) }
        client.onPageEnd = {
            blockLog = repo.engine.blockSnapshot()
            blocked = repo.engine.pageBlocks
            // element hiding (cosmetic '##selector' rules from the enabled lists):
            // hides now and keeps hiding dynamically injected elements — this is what
            // removes YouTube's "Open app" banner / promo strips
            runCatching {
                repo.engine.cosmeticJs(pageHost)?.let { js -> webView.evaluateJavascript(js, null) }
            }
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

        // ── long press → action menu ──────────────────────────────────────────────
        // The WebView's long click only SUPPRESSES Chromium's long-press UI for
        // links / images (text keeps text selection); the menu comes from a
        // GestureDetector, which asks the page for the pressed node rather than
        // guessing from the touch point.
        // Nothing is consumed from the touch stream — a swallowed UP leaves the
        // WebView stuck, unresponsive until it navigates again.
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                // remember the element under the finger, then ask the page for the
                // focused node's href / img src / anchor text
                val hit = webView.hitTestResult
                pressHitUrl = hit.extra?.takeIf { it.startsWith("http") }
                pressHitImage = hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                val msg = longPressHandler.obtainMessage()
                msg.target = longPressHandler
                runCatching { webView.requestFocusNodeHref(msg) }
                // safety net: a WebView that never delivers the node message still gets
                // the menu from the element under the finger
                val firedAt = SystemClock.uptimeMillis()
                webView.postDelayed({
                    if (lastOfferAt < firedAt) {
                        pressHitUrl?.let { u -> offerLongPress(u, pressHitImage, null) }
                    }
                }, 400L)
            }
        })

        webView.setOnLongClickListener { v ->
            // images and image/area links are claimed by the app,
            // plain links keep Chromium's own long-press (it cancels their click),
            // text keeps text selection
            val type = (v as? WebView)?.hitTestResult?.type ?: 0
            type == WebView.HitTestResult.IMAGE_TYPE ||
                type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        }

        webView.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            false
        }

        // the first navigation waits for the window: a WebView that starts loading
        // while detached can finish without ever painting (a blank page that only a
        // tab switch / reload cures). Every (re)attach also kicks one layout + draw,
        // because switching screens detaches the whole browser hierarchy.
        pendingUrl = if (startUrl == BROWSER_HOME) BLANK_PAGE else startUrl
        webView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                pendingUrl?.let { u ->
                    pendingUrl = null
                    v.post { runCatching { webView.loadUrl(u) } }
                }
                v.requestLayout()
                v.post { runCatching { v.invalidate() } }
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
        // a background tab is not attached until selected — it still has to load,
        // so the first navigation goes out on its own after a moment
        webView.postDelayed({
            pendingUrl?.let { u ->
                pendingUrl = null
                runCatching { webView.loadUrl(u) }
            }
        }, 800L)
    }

    /** address snaps to the target so the row reflects what is loading even before commit. */
    fun load(url: String) {
        address = url
        webView.loadUrl(url)
    }

    /** the start page is Compose, not a page — its tab's WebView rests on a blank document. */
    private fun pageAddress(url: String?): String =
        if (url.isNullOrBlank() || url == BLANK_PAGE) BROWSER_HOME else url

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
