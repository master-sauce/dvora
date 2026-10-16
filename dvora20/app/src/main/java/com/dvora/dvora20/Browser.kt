package com.dvora.dvora20

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dvora.dvora20.adblock.BlockerChromeClient
import com.dvora.dvora20.adblock.BlockerEngine
import com.dvora.dvora20.adblock.BlockerWebViewClient
import com.dvora.dvora20.adblock.CapturedMedia
import com.dvora.dvora20.adblock.ListRepo
import com.dvora.dvora20.adblock.Media
import com.dvora.dvora20.adblock.ResourceProbe
import com.dvora.dvora20.adblock.Sniff
import com.dvora.dvora20.adblock.SniffName
import com.dvora.dvora20.adblock.SnifferJsInterface
import com.dvora.dvora20.adblock.SnifferStore
import com.dvora.dvora20.adblock.SniffRow
import com.dvora.dvora20.adblock.Whitelist
import java.io.File
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** default page shown when the top-bar browser button is tapped. */
const val BROWSER_HOME = "https://duckduckgo.com/"

/**
 * Where yt-dlp stores captured media — the picker-chosen folder from the
 * browser settings tab (prefs `yt_dir`), defaulting to `Download/dvora`.
 * Falls back to the app-private external files dir if nothing is writable.
 */
@Suppress("DEPRECATION")
fun ytSaveDir(context: Context): File? {
    val prefs = context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
    val custom = prefs.getString("yt_dir", null)?.takeIf { it.isNotBlank() }
    val dir = custom?.let { File(it) }
        ?: File(Environment.getExternalStorageDirectory(), "Download/dvora")
    return if (dir.canWrite() || dir.mkdirs()) dir
    else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "media") }
        ?: context.filesDir
}

/**
 * The full-screen, in-app, bee-themed browser with the built-in
 * ad/tracker blocker: toolbar (history / reload / favicon+title / theme),
 * address bar with GO, load-progress indicator, shield button with a
 * per-page blocked-count badge, per-site whitelist panel, SSL user dialog,
 * `<input type="file">` picker, and DownloadManager downloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String,
    repo: ListRepo,
    onBack: () -> Unit,
    onToggleDark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // the in-app browser UI is always rendered in the dark theme — the light
    // variants of these surfaces look broken (odd whites) against the webview

    val headerBg = BeeColors.DarkComb

    val pageBg = BeeColors.DarkComb

    val textColor = BeeColors.DarkOnSurface

    val subColor = BeeColors.DarkOnSurface.copy(alpha = 0.7f)

    // ── screen state ──────────────────────────────────────────────────────────
    var address by remember { mutableStateOf(initialUrl.ifBlank { BROWSER_HOME }) }
    var pageTitle by remember { mutableStateOf("") }
    var favicon by remember { mutableStateOf<Bitmap?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageHost by remember { mutableStateOf("") }
    var blocked by remember { mutableIntStateOf(0) }
    var blockLog by remember { mutableStateOf<List<BlockerEngine.BlockRecord>>(emptyList()) }
    var ssl by remember { mutableStateOf<Pair<Uri?, SslErrorHandler>?>(null) }
    var pendingChooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }
    var crossNav by remember { mutableStateOf<Pair<String, String>?>(null) }   // (url, host) awaiting confirm
    var resTick by remember { mutableIntStateOf(0) }                           // bumps live while the panel is open
    var barVisible by remember { mutableStateOf(true) }                         // hide/show the bottom toolbar
    var urlBar by remember { mutableStateOf(false) }                             // editable url/search row below the toolbar
    var urlDraft by remember { mutableStateOf("") }                               // field text while the user edits
    var urlEditing by remember { mutableStateOf(false) }                           // true once the row text is user-owned
    val urlFocus = remember { FocusRequester() }
    var hasStorage by remember { mutableStateOf(Build.VERSION.SDK_INT >= 29) }
    var video by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }   // fullscreen video overlay

    // the whole site's whitelist — kept live; the interceptor reads this supplier per request
    var allowed by remember { mutableStateOf(Whitelist.hosts(context)) }

    // ── yt-dlp: media download of what was captured on this page — owned by the
    // foreground service from here on; here its live state is polled for badge / row ──
    var ytPicker by remember { mutableStateOf(false) }   // dialog listing capturable media
    var ytSheet by remember { mutableStateOf(false) }    // live downloads screen (running jobs + rest)
    var yt by remember { mutableIntStateOf(YtCtl.pct) }            // -1 idle · 0..99 % (first running job)
    var ytCount by remember { mutableIntStateOf(YtCtl.count) }     // how many downloads run concurrently
    var ytUrl by remember { mutableStateOf(YtCtl.url) }            // what is downloading now
    var ytBadge by remember { mutableIntStateOf(0) }               // captured media urls → button badge
    var ytDone by remember { mutableStateOf<File?>(null) }         // saved file awaiting its done dialog (queued)
    var ytPending by remember { mutableStateOf<String?>(null) }    // url to resume once the storage prompt is done
    var ytErr by remember { mutableStateOf<Pair<String, String>?>(null) }  // (url, failure text) dialog queue
    var mgr by remember { mutableStateOf(Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) }

    // ── one-time objects ──────────────────────────────────────────────────────
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setGeolocationEnabled(false)
            setBackgroundColor(0xFF1C1500.toInt())
        }
    }
    // the injected sniffer script talks to the app through this bridge — built after the
    // WebView so the UA provider can read the WebView's real User-Agent string
    val jsInterface = remember { SnifferJsInterface(context) { webView.settings.userAgentString } }
    val client = remember { BlockerWebViewClient(context, repo) { allowed.toSet() } }.also { c ->
        c.injectSniffer = { wv -> runCatching { wv.evaluateJavascript(SnifferJsInterface.SCRIPT, null) } }
    }
    val chrome = remember { BlockerChromeClient() }

    // ── launchers ─────────────────────────────────────────────────────────────
    val dlPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasStorage = granted
        if (!granted) ytPending = null                    // denied — forget the pending media retry
    }
    // asked once at the first download — without it only the in-app status row tracks yt-dlp
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // the default folder /dvora sits outside the app sandbox — one-time "all files" grant on API 30+
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) mgr = Environment.isExternalStorageManager()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = pendingChooser ?: return@rememberLauncherForActivityResult
        pendingChooser = null
        val data = if (result.resultCode == Activity.RESULT_OK) result.data else null
        if (data == null) {
            cb.onReceiveValue(null)
        } else {
            val picked = mutableListOf<Uri>()
            data.data?.let { picked.add(it) }
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { picked.add(it) }
            }
            cb.onReceiveValue(if (picked.isEmpty()) null else picked.toTypedArray())
        }
    }

    fun pickFile(params: WebChromeClient.FileChooserParams) {
        filePicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val types = params.acceptTypes
            type = if (!types.isNullOrEmpty()) types.first() else "image/*"
            if (!types.isNullOrEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types)
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        })
    }

    // ── yt-dlp ────────────────────────────────────────────────────────────────────

    /** kick off a media download — the foreground service runs up to YtCtl.MAX of them concurrently. */
    fun startYt(given: String) {
        val url = given.substringBefore('#')     // page hash fragments are noise for yt-dlp
        // 1DM resolves media urls to the END (redirects already followed by the probe) before
        // any engine sees them — the row keeps the original, the engine gets the final url
        val cap = SnifferStore.byUrl(url)
        val target = cap?.finalUrl?.takeIf { it.startsWith("http") } ?: url
        if (YtCtl.contains(target)) {
            Toast.makeText(context, localeStr(context, R.string.yt_already), Toast.LENGTH_SHORT).show()
            return
        }
        // the picker stays open — tapping more rows queues more downloads side by side
        // old APIs need the legacy write grant; new ones the one-time "all files" grant — the default
        // folder /dvora sits outside the app sandbox
        if (Build.VERSION.SDK_INT < 30 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ytPending = url
            dlPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= 30 && !mgr) {
            ytPending = url
            Toast.makeText(context, localeStr(context, R.string.yt_need_store), Toast.LENGTH_LONG).show()
            grant.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)     // once; the download runs without too
        }
        val dir = ytSaveDir(context) ?: run {
            Toast.makeText(context, localeStr(context, R.string.yt_dl_fail), Toast.LENGTH_LONG).show()
            return
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(context, localeStr(context, R.string.yt_dl_fail), Toast.LENGTH_LONG).show()
            return
        }
        // the saved file takes the current window title — every capture belongs to this page;
        // '%' must die too — yt-dlp's -o is a template and a bare % breaks it
        val title = pageTitle.replace(Regex("[\\\\/:*?\"<>|%]"), "_").trim().take(120).takeIf { it.isNotBlank() }
        // registers the job (unique output name + own process id) — null = the queue is full
        val job = YtCtl.add(target, dir, title) ?: run {
            Toast.makeText(context, localeStr(context, R.string.yt_queue_full), Toast.LENGTH_SHORT).show()
            return
        }
        yt = 0
        ytCount = YtCtl.count
        ytUrl = target
        // 1DM-style header replay: prefer the exact referer/cookie the page used for this
        // resource (from the sniffer capture), fall back to the page itself + cookie jar
        val referer = cap?.referer ?: address.takeIf { it.startsWith("http") }
        val cookie = cap?.cookie ?: CookieManager.getInstance().getCookie(url)
        // the page's EXACT request headers for this url (urlHeadersCache) — the engines
        // replay them verbatim, exactly like 1DM's Connect; Accept-Encoding stays out
        // (raw gzip would corrupt everything that isn't yt-dlp)
        val headersCsv = SnifferStore.headersFor(url)?.entries
            ?.filter {
                !it.key.equals("Range", true) && !it.key.equals("Host", true) &&
                    !it.key.equals("Accept-Encoding", true) && it.value.isNotEmpty()
            }
            ?.joinToString("\n") { "${it.key}: ${it.value}" }
        YtDownloadService.start(context, target, dir, job.baseName, referer, webView.settings.userAgentString, cookie, job.proc, headersCsv)
    }


    /** abort every running yt-dlp process — from the in-app row or the notification action */
    fun stopYt() {
        if (YtCtl.count == 0) return
        YtCtl.cancel(context)
        yt = -1
        ytCount = 0
        ytDone = null
        Toast.makeText(context, localeStr(context, R.string.yt_cancelled), Toast.LENGTH_SHORT).show()
    }

    /** a picker/sheet row tap → the right download route — shared by both dialogs. */
    fun openRow(r: SniffRow, rows: List<SniffRow>, close: () -> Unit) {
        when {
            r.isBlob -> {
                // blob URLs can't go to yt-dlp — the JS side fetches the in-memory Blob,
                // base64-chunks it to the bridge, native writes the file
                val js = "(window.__dvoraSaveBlob && window.__dvoraSaveBlob('" + r.url + "'))"
                runCatching { webView.evaluateJavascript(js, null) }
                close()
            }
            r.isSegment -> {
                // one fragment ≠ the movie — yt-dlp needs the MASTER PLAYLIST:
                // captured one → guessed sibling file of the fragment → else the page url
                val pl = rows.firstOrNull { it.isPlaylist }?.url
                    ?: if (r.url.lowercase().contains(".mp4/")) {
                        r.url.substringBeforeLast('/') + "/index.m3u8"
                    } else null
                startYt(pl ?: address)
            }
            else -> startYt(r.url)
        }
    }

    // ── sniffer merges — 1DM-style captures (JS bridge: real headers/body) + reqLog url-only rows ──
    fun sniffRows(): List<SniffRow> {
        val fromStore = SnifferStore.all().map { c ->
            SniffRow(
                url = c.url,
                label = if (c.kind == "blob") "BLOB" else c.kind.uppercase(),
                size = c.size,
                name = c.filename,
                isSegment = isSegment(c.url),
                isPlaylist = c.kind == "m3u8" || c.kind == "mpd",
                isBlob = c.kind == "blob",
                duration = c.duration,
                parts = c.parts
            )
        }
        val known = fromStore.mapTo(mutableSetOf()) { it.url.lowercase().substringBefore('#') }
        // ── 1DM's Connect on the request log: candidates get probed (media-tagged
        // first, then newest-first) and the RESPONSE decides — extension-less media
        // endpoints never hide behind a weird url shape. Only a video/audio/playlist
        // answer promotes into a capture row; static-file extensions never burn a slot. ──
        val staticExts = setOf(
            "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "cur", "avif",
            "woff", "woff2", "ttf", "otf", "eot", "xml", "txt", "html", "htm", "json", "map"
        )
        run {
            val cands = repo.engine.reqSnapshot().filter { !it.blocked }
                .filter { it.tag == "media" || it.tag == "api" || it.tag == "req" }
            (cands.filter { it.tag == "media" } + cands.filter { it.tag != "media" }.asReversed())
                .forEach { r ->
                    if (SnifferStore.byUrl(r.url) != null) return@forEach
                    val ext = r.url.substringBefore('?').substringAfterLast('.', "").lowercase()
                    if (r.tag != "media" && ext in staticExts) return@forEach    // static file — no media there
                    SnifferStore.markProbe(r.url) {
                        ResourceProbe.probeAsync(
                            r.url,
                            address.takeIf { it.startsWith("http") },
                            CookieManager.getInstance().getCookie(r.url),
                            webView.settings.userAgentString
                        )
                    }
                }
        }
        val extras = repo.engine.reqSnapshot().asSequence()
            .filter { !it.blocked }
            .filter { r ->
                // everything the page itself flagged as media (Accept: video/audio headers on
                // extension-less CDN urls), everything the loose sniffer matches, and every
                // downloadable file extension — /api/ noise only drops without a media signal
                r.tag == "media" || Media.sniff(r.url) != null ||
                    (!r.url.contains("/api/", true) && Sniff.kind(r.url, "", null, -1) != null)
            }
            .map { it.url }
            .distinctBy { Media.normalize(it) }
            .filter { it.lowercase().substringBefore('#') !in known }
            .onEach { u ->
                // probe extras once per url per page — fills size/name/duration after redirects;
                // bare fragments can't enrich (Sniff.kind rejects them) so they burn no probe slot
                if (!isSegment(u)) {
                    SnifferStore.markProbe(u) {
                        ResourceProbe.probeAsync(
                            u,
                            address.takeIf { it.startsWith("http") },
                            CookieManager.getInstance().getCookie(u),
                            webView.settings.userAgentString
                        )
                    }
                }
            }
            .map { u ->
                val k = Sniff.kind(u, "", null, -1) ?: Media.sniff(u) ?: "video"
                SniffRow(u, k.uppercase(), -1L, SniffName.resolve(u, "", null), isSegment(u), k == "m3u8" || k == "mpd", isBlob = false)
            }
            .toList()
        return (fromStore + extras)
            .distinctBy { it.url.lowercase().substringBefore('#') }
            .sortedBy { row -> when { row.isPlaylist -> 0; row.isSegment -> 2; else -> 1 } }
    }

    fun fmtSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1e9)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1e6)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1e3)
        else -> "$bytes B"
    }

    /** seconds → m:ss / h:mm:ss — the 1DM-style duration chip on every media row */
    fun fmtDur(sec: Double): String {
        if (sec <= 0) return ""
        val t = sec.toInt()
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    // resume the yt download that waited on the storage prompt — after startYt is declared above it
    LaunchedEffect(mgr, hasStorage, ytPending) {
        val u = ytPending ?: return@LaunchedEffect
        ytPending = null
        startYt(u)
    }

    // polls the download service — the download keeps running while the screen is disposed; row / badge / dialogs mirror it
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            val p = YtCtl.pct
            if (p != yt) yt = p
            val c = YtCtl.count
            if (c != ytCount) ytCount = c
            val u = YtCtl.url
            if (u != ytUrl) ytUrl = u
            // done/fail dialogs consume their queues one at a time — a multi-download batch loses none
            if (ytDone == null) YtCtl.popDone()?.let { ytDone = it }
            if (ytErr == null) YtCtl.popErr()?.let { ytErr = it }
            // badge = distinct captures (sniffer + reqLog), not raw request count
            val n = sniffRows().size
            if (n != ytBadge) ytBadge = n
        }
    }

    /**
     * submits the url/search row: url-like input navigates directly, anything else
     * becomes a duckduckgo search. `address` snaps to the target so the row reflects
     * what is loading even if the page client reports nothing yet.
     */
    fun submitUrl() {
        val raw = if (urlEditing) urlDraft.trim() else address.trim()
        if (raw.isEmpty()) return
        // a url must carry a scheme or a dotted host — bare words / phrases are searches
        val host = raw.substringBefore('/').substringBefore('?').substringBefore('#')
        val looksLikeUrl = raw.contains("://") || (host.contains('.') && host.isNotEmpty())
        val target = if (looksLikeUrl) {
            if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        } else {
            "https://duckduckgo.com/?q=" + Uri.encode(raw, "")
        }
        urlEditing = false
        urlDraft = ""
        address = target
        webView.loadUrl(target)
    }

    /** opens the downloads folder — [dir] overrides (the done dialog passes the file's own folder). */
    fun openDir(dir: File? = null) {
        val d = dir ?: ytSaveDir(context) ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.fromFile(d), "resource/folder")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(context, d.path, Toast.LENGTH_LONG).show()
        }
    }

    fun enqueueDownload(url: String, disposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) return
        if (!hasStorage) {
            if (Build.VERSION.SDK_INT < 29) {          // needs the runtime grant on old APIs
                dlPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        // filename: Content-Disposition (filename* charset-aware) first, then the url path
        val name = SniffName.fromDisposition(disposition)
            ?: url.substringAfterLast('/')?.substringBefore('?')?.trim()?.takeIf { it.isNotEmpty() }
        val safe = (name ?: "dvora-download-${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            .take(120)
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(safe)
                .setDescription(context.getString(R.string.dl_started_desc))
                .setMimeType(mimeType ?: "application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 1DM-style header replay — the page's own UA / referer / cookies, not a fake one
                .addRequestHeader("User-Agent", webView.settings.userAgentString)
            (address.takeIf { it.startsWith("http") })?.let {
                req.addRequestHeader("Referer", it)
            }
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (Build.VERSION.SDK_INT >= 29) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe)
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                req.setDestinationUri(Uri.fromFile(File(dir, safe)))
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(context, localeStr(context, R.string.dl_started), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, localeStr(context, R.string.dl_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ── one-time wiring ───────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        if (Build.VERSION.SDK_INT < 29) dlPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        webView.addJavascriptInterface(jsInterface, "JSInterface")     // 1DM's JSInterface bridge
        webView.webViewClient = client
        webView.webChromeClient = chrome
        webView.setDownloadListener(object : DownloadListener {
            override fun onDownloadStart(
                url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long
            ) {
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
                enqueueDownload(url, contentDisposition, mimeType)
            }
        })

        chrome.onFileChooser = { cb, params -> pendingChooser = cb; pickFile(params) }
        chrome.onVideo = { v, cb -> video = (v to cb) }
        chrome.onVideoExited = { video = null }
        chrome.onTitle = { pageTitle = it }
        chrome.onProgress = { progress = it }
        chrome.onIcon = { favicon = it }
        client.onUrl = { address = it }
        client.onPageStart = { host, url ->
            if (video != null) {                           // page changed → leave fullscreen video
                video?.let { it.second.onCustomViewHidden() }
                video = null
            }
            pageHost = host
            pageTitle = ""
            favicon = null
            blocked = 0
            blockLog = emptyList()
            SnifferStore.newPage()                       // 1DM keeps one capture list per page
            address =
                url                              // bar always tracks the page actually loaded (redirects / history)
        }
        client.onActivity = { if (sheetVisible) resTick++ }
        client.onCrossNav = { url, host -> crossNav = url to host }
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
        client.onSsl = { uri, handler -> ssl = (uri to handler) }

        webView.loadUrl(address)
    }



    BackHandler {
        if (sheetVisible) {
            sheetVisible = false; return@BackHandler
        }   // resources panel closes first
        if (video != null) {                                 // then leave fullscreen video
            video?.let { it.second.onCustomViewHidden() }
            video = null
            return@BackHandler
        }
        if (webView.canGoBack()) webView.goBack() else onBack()          // then page history, then exit
    }

    // ── SSL dialog — allow once / go back, never silently proceed ────────────
    ssl?.let { (uri, handler) ->
        AlertDialog(
            onDismissRequest = { ssl = null; handler.cancel() },

            containerColor = BeeColors.DarkComb,
            title = { Text("\ud83d\udd12 " + L(R.string.ssl_title), color = BeeColors.HoneyGold) },
            text = {
                Column {
                    Text(L(R.string.ssl_body), color = textColor)
                    Spacer(Modifier.height(8.dp))
                    Text(uri?.toString() ?: "", color = subColor, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { ssl = null; handler.proceed() }) {
                    Text(
                        L(R.string.ssl_proceed),
                        color = BeeColors.DeepAmber
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { ssl = null; handler.cancel() }) {
                    Text(
                        L(R.string.ssl_cancel),
                        color = BeeColors.DeepAmber
                    )
                }
            }
        )
    }

    // ── cross-site confirm — asked whenever a page link would change the site ──
    crossNav?.let { (url, host) ->
        AlertDialog(
            onDismissRequest = { crossNav = null; address = webView.url ?: "" },

            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.nav_title), color = BeeColors.HoneyGold) },
            text = {
                Column {
                    Text(
                        host,
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(url, color = subColor, fontSize = 10.sp, maxLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // follow-up redirects of the approved host must not re-prompt
                    client.docHost = Uri.parse(url).host?.lowercase() ?: ""
                    crossNav = null
                    webView.loadUrl(url)
                }) {
                    Text(L(R.string.nav_go), color = BeeColors.DeepAmber, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { crossNav = null; address = webView.url ?: "" }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── yt-dlp picker: only the media captured for this page ────────────────
    // live while open — streams captured after the dialog is up must appear without reopening
    var ytTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ytPicker) {
        if (!ytPicker) return@LaunchedEffect
        while (true) {
            delay(400); ytTick++
        }
    }
    if (ytPicker) {
        // merged capture rows — sniffer captures (real headers / sizes / names) + reqLog extras.
        // playlists / masters on top, bare fragments at the bottom
        val rows = remember(ytTick) { sniffRows() }
        AlertDialog(
            onDismissRequest = { ytPicker = false },

            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.yt_picker), color = BeeColors.HoneyGold) },
            text = {
                Box(Modifier.heightIn(max = 420.dp)) {
                    if (rows.isEmpty()) {
                        Text(L(R.string.yt_none), fontSize = 12.sp, color = textColor)
                    } else {
                        LazyColumn {
                            items(rows) { r ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        openRow(r, rows) { ytPicker = false }
                                    }.padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        listOfNotNull(
                                            r.label,
                                            fmtDur(r.duration).takeIf { it.isNotEmpty() },
                                            if (r.parts > 0) context.getString(R.string.yt_parts, r.parts) else null,
                                            fmtSize(r.size).takeIf { it.isNotEmpty() }
                                        ).joinToString(" · "),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            r.isSegment -> BeeColors.DeepAmber
                                            r.isPlaylist -> BeeColors.HoneyGold
                                            else -> BeeColors.HoneyGold
                                        }
                                    )
                                    Text(
                                        r.name ?: r.url,
                                        fontSize = 10.sp,
                                        color = textColor,
                                        maxLines = 2
                                    )
                                    if (r.name != null) {
                                        Text(
                                            r.url,
                                            fontSize = 9.sp,
                                            color = subColor,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { ytPicker = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── live downloads screen — every running job with its own progress + cancel,
    // then the rest of this page's downloadables, tap to queue another ──────────
    var dlTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ytSheet) {
        if (!ytSheet) return@LaunchedEffect
        while (true) {
            delay(400); dlTick++
        }
    }
    if (ytSheet) {
        val jobs = remember(dlTick) { YtCtl.running().map { Triple(it.url, it.pct, it) } }
        val restRows = remember(dlTick) { sniffRows() }
        val liveUrls = jobs.mapTo(mutableSetOf()) { it.first.lowercase().substringBefore('#') }
        val rest = restRows.filter { it.url.lowercase().substringBefore('#') !in liveUrls }
        AlertDialog(
            onDismissRequest = { ytSheet = false },
            containerColor = BeeColors.DarkComb,
            title = { Text("⬇ " + L(R.string.yt_sheet_title), color = BeeColors.HoneyGold) },
            text = {
                Box(Modifier.heightIn(max = 460.dp)) {
                    LazyColumn {
                        items(jobs) { t ->
                            val (jurl, jpct, job) = t
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        jurl,
                                        fontSize = 10.sp,
                                        color = textColor,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "$jpct%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BeeColors.FoundGreen
                                    )
                                    IconButton(
                                        onClick = {
                                            YtCtl.cancel(context, job)     // this job only — the rest keep running
                                            Toast.makeText(context, localeStr(context, R.string.yt_cancelled), Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            L(R.string.yt_cancel),
                                            tint = BeeColors.NotFoundRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { jpct / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = BeeColors.FoundGreen,
                                    trackColor = BeeColors.FoundGreen.copy(alpha = 0.15f)
                                )
                            }
                        }
                        if (rest.isNotEmpty()) {
                            item {
                                Text(
                                    L(R.string.yt_sheet_rest),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = subColor,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                                )
                            }
                            items(rest) { r ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        openRow(r, restRows) { ytSheet = false }
                                    }.padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        listOfNotNull(
                                            r.label,
                                            fmtDur(r.duration).takeIf { it.isNotEmpty() },
                                            if (r.parts > 0) context.getString(R.string.yt_parts, r.parts) else null,
                                            fmtSize(r.size).takeIf { it.isNotEmpty() }
                                        ).joinToString(" · "),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BeeColors.HoneyGold
                                    )
                                    Text(r.name ?: r.url, fontSize = 10.sp, color = textColor, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { ytSheet = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── yt-dlp failure reason ─────────────────────────────────────────────────
    ytErr?.let { (failedUrl, msg) ->
        AlertDialog(

            // close AND drop the source so the poll loop can't re-pop the same failure

            onDismissRequest = { ytErr = null },

            containerColor = BeeColors.DarkComb,


            title = { Text(L(R.string.yt_err_title), color = BeeColors.HoneyGold) },

            text = {

                Column {

                    Text(msg, color = textColor, fontSize = 11.sp)

                    Spacer(Modifier.height(6.dp))

                    Text(failedUrl, color = subColor, fontSize = 9.sp, maxLines = 3)

                }

            },

            confirmButton = {

                TextButton(onClick = {
                    ytErr = null
                }) {

                    Text(L(R.string.ok), color = BeeColors.DeepAmber)

                }

            },

            dismissButton = {
                TextButton(onClick = {
                    copyToClipboard(context, "$msg\n$failedUrl")
                    Toast.makeText(context, localeStr(context, R.string.rs_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Text(L(R.string.yt_copy_err), color = BeeColors.HoneyGold)
                }
            }
        )
    }

    // ── saved media — one dialog per finished download, queued across a multi-download batch ──
    ytDone?.let { f ->
        AlertDialog(
            onDismissRequest = { ytDone = null },
            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.yt_done), color = BeeColors.HoneyGold) },
            text = { Text(f.name, color = textColor, fontSize = 11.sp, maxLines = 3) },
            confirmButton = {
                TextButton(onClick = { ytDone = null; openDir(f.parentFile) }) {
                    Text(L(R.string.med_open), color = BeeColors.HoneyGold)
                }
            },
            dismissButton = {
                TextButton(onClick = { ytDone = null }) {
                    Text(L(R.string.ok), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── UI ──────────────────────────────────────────────────────────────────────
    // edge-to-edge; the toolbar is a collapsible row at the bottom of the screen
    Box(modifier = modifier.fillMaxSize().background(pageBg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ── top row: live url / search — toggleable by the toolbar button ──
            if (urlBar) {
                LaunchedEffect(Unit) {
                    urlEditing = false
                    urlDraft = address
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .background(headerBg)
                ) {
                    Icon(
                        Icons.Default.Link,
                        null,
                        tint = if (urlEditing) BeeColors.FoundGreen else BeeColors.HoneyGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = if (urlEditing) urlDraft else address,
                        onValueChange = { urlEditing = true; urlDraft = it },
                        modifier = Modifier.weight(1f).focusRequester(urlFocus),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = textColor),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            focusedBorderColor = BeeColors.HoneyGold,
                            unfocusedBorderColor = BeeColors.HoneyGold.copy(alpha = 0.25f),
                            focusedContainerColor = headerBg,
                            unfocusedContainerColor = headerBg
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submitUrl() }, onDone = { submitUrl() }),
                        placeholder = {
                            Text("https://… or search term", fontSize = 12.sp, color = textColor.copy(alpha = 0.35f))
                        }
                    )
                    IconButton(onClick = { submitUrl() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.PlayArrow, L(R.string.nav_go), tint = BeeColors.HoneyGold)
                    }
                }
            }

            AndroidView(
                factory = { webView },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )

            if (progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = BeeColors.DeepAmber,
                    trackColor = BeeColors.DeepAmber.copy(alpha = 0.2f)
                )
            }

            // yt-dlp download active — slim cancelable status row (shown even when the toolbar is hidden)
            if (yt in 0..99) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(headerBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Download, null, tint = BeeColors.FoundGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ytCount > 1) "↓$ytCount · $ytUrl" else ytUrl,
                        fontSize = 10.sp,
                        color = textColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { yt / 100f },
                        modifier = Modifier.width(90.dp).height(8.dp),
                        color = BeeColors.FoundGreen,
                        trackColor = BeeColors.FoundGreen.copy(alpha = 0.15f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$yt%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BeeColors.FoundGreen
                    )
                    IconButton(onClick = { stopYt() }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.Close,
                            L(R.string.yt_cancel),
                            tint = BeeColors.HoneyGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (barVisible) {
                Column(Modifier.navigationBarsPadding().fillMaxWidth()) {
                    // toolbar (bottom) — back / fwd / reload / home / page resources / url row toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 4.dp).background(headerBg)
                    ) {
                        IconButton(
                            onClick = { if (webView.canGoBack()) webView.goBack() },
                            enabled = webView.canGoBack()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack, L(R.string.cd_back_page),
                                tint = if (webView.canGoBack()) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(
                            onClick = { if (webView.canGoForward()) webView.goForward() },
                            enabled = webView.canGoForward()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward, L(R.string.cd_fwd_page),
                                tint = if (webView.canGoForward()) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(
                                    alpha = 0.3f
                                )
                            )
                        }
                        IconButton(onClick = { webView.reload() }) {
                            Icon(Icons.Default.Refresh, L(R.string.cd_reload), tint = BeeColors.HoneyGold)
                        }
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Home, L(R.string.cd_home), tint = BeeColors.HoneyGold)
                        }
                        IconButton(onClick = { sheetVisible = !sheetVisible }) {
                            Icon(
                                Icons.Default.Web,
                                L(R.string.cd_network),
                                tint = BeeColors.HoneyGold,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(onClick = {
                            if (yt in 0..99) ytSheet = true      // running → the live downloads screen
                            else ytPicker = true                 // idle → list captured media
                        }) {
                            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                                if (yt in 0..99) Text(
                                    if (ytCount > 1) "↓$ytCount" else "$yt%", fontSize = 11.sp,
                                    color = BeeColors.FoundGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                else Icon(Icons.Default.Download, L(R.string.yt_cd), tint = BeeColors.FoundGreen)
                                if (yt < 0 && ytBadge > 0) {      // media urls captured on this page → count badge
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .background(BeeColors.FoundGreen, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("$ytBadge", fontSize = 9.sp, color = Color.White, maxLines = 1)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { urlBar = !urlBar }) {
                            Icon(
                                if (urlBar) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                L(R.string.cd_bar_hide),
                                tint = BeeColors.HoneyGold
                            )
                        }
                    }
                }
            } else {
                // bar hidden — slim strip with one button that brings it back
                Box(
                    Modifier.navigationBarsPadding().fillMaxWidth().background(headerBg),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = { barVisible = true }) {
                        Icon(
                            Icons.Default.ExpandMore,
                            L(R.string.cd_bar_show),
                            tint = BeeColors.HoneyGold,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // the slide-up page-resources panel — every request of this page, api / media first-class
        AnimatedVisibility(
            visible = sheetVisible,
            enter = slideInVertically { 0 },
            exit = slideOutVertically { 0 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = if (barVisible) 62.dp else 0.dp)
        ) {
            Box(
                Modifier.navigationBarsPadding().fillMaxWidth().background(

                    BeeColors.DarkCell,

                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

                )
            ) {
                ResourceSheet(
                    host = pageHost,
                    log = remember(resTick, sheetVisible) { repo.engine.reqSnapshot() },
                    blocked = repo.engine.pageBlocks,
                    lifetime = repo.engine.lifetime,
                    onMedia = { ytPicker = true },
                    onDownload = { startYt(it) },
                    onClose = { sheetVisible = false }
                )
            }
        }

        // fullscreen video overlay (onShowCustomView) with its own close button
        video?.let { (v, cb) ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { v }, modifier = Modifier.fillMaxSize())
                IconButton(
                    onClick = { video = null; cb.onCustomViewHidden() },
                    modifier = Modifier.statusBarsPadding().align(Alignment.TopEnd).padding(8.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = L(R.string.cd_close), tint = Color.White)
                }
            }
        }
    }
}

/**
 * The panel opened by the toolbar resources button: EVERY request of this
 * page — page / js / css / img / frame / api / media — with live filters,
 * rule + list for each blocked one, and the lifetime counter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResourceSheet(
    host: String,
    log: List<BlockerEngine.ReqRecord>,
    blocked: Int,
    lifetime: Long,
    onMedia: (String) -> Unit,
    onDownload: (String) -> Unit,
    onClose: () -> Unit
) {
    val textColor = BeeColors.DarkOnSurface

    val subColor = BeeColors.DarkOnSurface.copy(alpha = 0.7f)

    val rowBg = BeeColors.DarkStripe

    val context = LocalContext.current

    val copied = L(R.string.rs_copied)
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }              // free-text search across every request
    var confirm by remember { mutableStateOf<String?>(null) } // long-pressed url awaiting download confirm

    val shown = remember(log, filter, query) {
        val base = when (filter) {
            "blocked" -> log.filter { it.blocked }
            "api" -> log.filter { it.tag == "api" }
            "media" -> log.filter { it.tag == "media" }
            else -> log
        }
        val q = query.trim()
        if (q.isEmpty()) base
        else base.filter { it.url.contains(q, ignoreCase = true) || it.tag.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxWidth().height(560.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "📡 $host",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${log.size} ${L(R.string.rs_req)} · $blocked ${L(R.string.rs_blocked)} · ${lifetime} ${L(R.string.sheet_lifetime)}",
                    fontSize = 10.sp,
                    color = subColor,
                    maxLines = 1
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = L(R.string.cd_close), tint = BeeColors.DeepAmber)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all", "blocked", "api", "media").forEach { key ->
                val active = filter == key
                val label = when (key) {
                    "blocked" -> L(R.string.chip_blocked)
                    "api" -> L(R.string.chip_api)
                    "media" -> L(R.string.chip_media)
                    else -> L(R.string.chip_all)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(
                            if (active) BeeColors.HoneyGold else BeeColors.DarkStripe,
                            RoundedCornerShape(9.dp)
                        )
                        .clickable { filter = key },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color.Black else textColor
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = textColor),
            placeholder = {
                Text(L(R.string.rs_search), fontSize = 12.sp, color = textColor.copy(alpha = 0.35f))
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                focusedBorderColor = BeeColors.HoneyGold,
                unfocusedBorderColor = BeeColors.HoneyGold.copy(alpha = 0.25f),
                focusedContainerColor = rowBg,
                unfocusedContainerColor = rowBg
            )
        )
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(L(R.string.rs_empty), fontSize = 11.sp, color = subColor)
        } else {
            LazyColumn {
                items(shown) { rec ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(
                                if (rec.blocked) BeeColors.NotFoundRed.copy(alpha = 0.18f) else rowBg,
                                RoundedCornerShape(8.dp)
                            )
                            .combinedClickable(
                                onClick = {
                                    copyToClipboard(context, rec.url)
                                    if (rec.tag == "media") onMedia(rec.url)   // media rows forward to the downloads dialog
                                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                                },
                                onLongClick = { confirm = rec.url }            // ask before queueing a download
                            )
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                rec.tag,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    rec.blocked -> BeeColors.NotFoundRed
                                    rec.tag == "media" -> BeeColors.HoneyGold
                                    rec.tag == "api" -> BeeColors.DeepAmber
                                    rec.tag == "page" -> BeeColors.FoundGreen
                                    else -> textColor
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                rec.url,
                                fontSize = 10.sp,
                                color = textColor,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rec.blocked && rec.rule.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(rec.rule, fontSize = 9.sp, color = BeeColors.DeepAmber, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // long-press → explicit confirm before the url joins the download queue
    confirm?.let { u ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.dl_confirm_title), color = BeeColors.HoneyGold) },
            text = { Text(u, color = textColor, fontSize = 10.sp, maxLines = 4) },
            confirmButton = {
                TextButton(onClick = { confirm = null; onDownload(u) }) {
                    Text(L(R.string.yt_down), color = BeeColors.FoundGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }
}


/** bare .ts/.m4s fragment file — yt-dlp can't turn one segment back into the full video/show; the master playlist row is the entry point. */
private fun isSegment(u: String): Boolean {
    val p = u.substringBefore('?').lowercase()
    return p.endsWith(".ts") || p.endsWith(".m4s")
}


/** notification cancel action — kills the yt-dlp process + stops the download service. */
class YtCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        YtCtl.cancel(context)
        Toast.makeText(context, "cancelled", Toast.LENGTH_SHORT).show()
    }
}
